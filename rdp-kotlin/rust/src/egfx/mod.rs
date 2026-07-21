//! EGFX (MS-RDPEGFX) client over DRDYNVC.
//!
//! Phase 3a scope: surface + cache management, SolidFill, frame ACKs.
//! Codec-decoded WireToSurface tiles land in 3b — without codec decode the
//! cache stays empty so cache replays are no-ops, but everything else works.
//!
//! Channel name: "Microsoft::Windows::RDS::Graphics".

use std::sync::{Arc, RwLock};

use ironrdp_core::{impl_as_any, Decode as _, Encode, EncodeResult, ReadCursor, WriteCursor};
use ironrdp_dvc::{DvcClientProcessor, DvcEncode, DvcMessage, DvcProcessor};
use ironrdp_graphics::zgfx::Decompressor;
use ironrdp_egfx::pdu::{
    Avc420BitmapStream, CapabilitiesAdvertisePdu, CapabilitiesV10Flags, CapabilitiesV81Flags, CapabilitySet, GfxPdu, Codec1Type, Codec2Type, FrameAcknowledgePdu, QueueDepth, WireToSurface1Pdu, WireToSurface2Pdu,
};
use ironrdp_pdu::PduResult;
use log::{debug, info, warn};

mod clear;
mod progressive;
mod surface;

use clear::ClearDecoder;
use progressive::ProgressiveDecoder;
use surface::SurfaceManager;

use crate::SessionState;

const CHANNEL_NAME: &str = "Microsoft::Windows::RDS::Graphics";

/// Wrapper so we can implement [`DvcEncode`] for upstream's [`GfxPdu`].
struct GfxClientMessage(GfxPdu);

impl Encode for GfxClientMessage {
    fn encode(&self, dst: &mut WriteCursor<'_>) -> EncodeResult<()> {
        self.0.encode(dst)
    }
    fn name(&self) -> &'static str {
        "GfxClientMessage"
    }
    fn size(&self) -> usize {
        self.0.size()
    }
}

impl DvcEncode for GfxClientMessage {}

/// EGFX processor: caps, frame ACKs, server-PDU logging.
///
/// Surface management and codec decoding land in egfx::surface and
/// egfx::rfx (Phase 3). For now we ACK every frame so the server doesn't
/// throttle at `max_unacknowledged_frame_count` (FreeRDP-style: queue_depth=0
/// means "no backlog, please send the next frame").
pub struct EgfxProcessor {
    state: Arc<RwLock<SessionState>>,
    capabilities_received: bool,
    server_pdu_count: u64,
    /// MS-RDPEGFX wraps every DVC payload in an RDP_SEGMENTED_DATA PDU
    /// with ZGFX (RDP 8.0) bulk compression. The decompressor keeps a
    /// 2.5 MB sliding history shared across the whole channel lifetime.
    zgfx: Decompressor,
    /// Total EndFrame count we've seen — included in every FrameAck so
    /// the server can correlate decode progress.
    total_frames_decoded: u32,
    surfaces: SurfaceManager,
    /// ClearCodec context (sequence counter, glyph + vbar caches). The
    /// decoder is per-channel, not per-surface — the spec requires the
    /// caches to survive `ResetGraphics`.
    clear_decoder: ClearDecoder,
    /// RemoteFxProgressive context (sync state, context flags, IDWT
    /// scratch buffers). Per-channel, survives across PDUs.
    progressive_decoder: ProgressiveDecoder,
    /// RDP 6.0 planar bitmap decoder (xrdp encodes greeter/session tiles
    /// as Codec1Type::Planar). Holds a reusable planes buffer.
    planar_decoder: ironrdp_graphics::rdp6::BitmapStreamDecoder,
    /// #425: advertise H.264/AVC420 support (KRDP). Decode itself is done by
    /// the host-registered [`crate::Avc420Decoder`] (MediaCodec on Android),
    /// reached through `state.avc_decoder`.
    avc_enabled: bool,
}

impl EgfxProcessor {
    /// `progressive_upgrade` enables WBT_TILE_UPGRADE refinement decoding
    /// (#418) — a hidden/debug opt-in while the upgrade path is verified
    /// against real Windows captures. Default path passes `false`.
    pub fn new(state: Arc<RwLock<SessionState>>, progressive_upgrade: bool, avc_enabled: bool) -> Self {
        let mut progressive_decoder = ProgressiveDecoder::new();
        progressive_decoder.set_upgrade_enabled(progressive_upgrade);
        Self {
            state,
            capabilities_received: false,
            server_pdu_count: 0,
            zgfx: Decompressor::new(),
            total_frames_decoded: 0,
            surfaces: SurfaceManager::new(),
            clear_decoder: ClearDecoder::new(),
            progressive_decoder,
            planar_decoder: ironrdp_graphics::rdp6::BitmapStreamDecoder::default(),
            avc_enabled,
        }
    }
}

impl_as_any!(EgfxProcessor);

impl DvcProcessor for EgfxProcessor {
    fn channel_name(&self) -> &str {
        CHANNEL_NAME
    }

    /// Sent immediately after the DVC is created. Advertise V10 with
    /// `AVC_DISABLED` so the server picks ClearCodec / RemoteFX-Progressive /
    /// classic RemoteFX over AVC. Codec-version-only restriction (e.g.
    /// advertising V8) does *not* limit the server to classic RemoteFX —
    /// codec selection is per-tile by content type, independent of cap
    /// version, so Windows still emits ClearCodec for desktop UI either way.
    fn start(&mut self, _channel_id: u32) -> PduResult<Vec<DvcMessage>> {
        // #425: advertising AVC420_ENABLED lets an H.264-only server (KRDP)
        // drive the session. Gated on `RdpConfig.avc_enabled` (threaded in via
        // `EgfxProcessor::new`), which requires the host to have registered an
        // `Avc420Decoder` (MediaCodec on Android) — else negotiated AVC tiles
        // are dropped and the screen stays black. The Android app enables it by
        // default (KRDP-verified); `HAVEN_RDP_AVC=1` is an additional OR for the
        // host `rdp-cli` capture harness. When off → V10 AVC_DISABLED (server
        // picks ClearCodec / RemoteFX-Progressive).
        let avc = self.avc_enabled || std::env::var("HAVEN_RDP_AVC").is_ok();
        let caps = if avc {
            // Advertise ONLY V8.1 with AVC420_ENABLED. KRDP (FreeRDP server) only
            // encodes AVC420/YUV420, gated on the V8.1 AVC420_ENABLED flag, and
            // FreeRDP always *selects the highest advertised version* — so adding
            // V10 makes it pick V10 (which it reads as "YUV420 false") and it then
            // has nothing to send. V8.1-only forces the YUV420 path. AVC444 (V10)
            // is a later slice once we decode it. #425.
            info!("EGFX: sending CapabilitiesAdvertise(V8_1 AVC420_ENABLED)");
            CapabilitiesAdvertisePdu::from_typed(&[CapabilitySet::V8_1 {
                flags: CapabilitiesV81Flags::AVC420_ENABLED,
            }])
        } else {
            info!("EGFX: sending CapabilitiesAdvertise(V10, AVC_DISABLED)");
            CapabilitiesAdvertisePdu::from_typed(&[CapabilitySet::V10 {
                flags: CapabilitiesV10Flags::AVC_DISABLED,
            }])
        };
        let msg: DvcMessage = Box::new(GfxClientMessage(GfxPdu::CapabilitiesAdvertise(caps)));
        Ok(vec![msg])
    }

    fn process(&mut self, _channel_id: u32, payload: &[u8]) -> PduResult<Vec<DvcMessage>> {
        // Step 1: ZGFX decompress (every EGFX wire payload is wrapped).
        let mut decompressed = Vec::with_capacity(payload.len() * 4);
        if let Err(e) = self.zgfx.decompress(payload, &mut decompressed) {
            warn!(
                "EGFX zgfx decompress failed ({e:?}); skipping {} byte payload",
                payload.len()
            );
            return Ok(Vec::new());
        }
        debug!(
            "EGFX zgfx in={} out={} (ratio {:.2}x)",
            payload.len(),
            decompressed.len(),
            decompressed.len() as f32 / payload.len().max(1) as f32
        );
        // Step 2: decode every concatenated GfxPdu in the buffer. A single
        // DVC message often carries StartFrame / WireToSurface* / EndFrame
        // back-to-back for one surface update.
        let mut out_messages: Vec<DvcMessage> = Vec::new();
        let mut cur = ReadCursor::new(&decompressed);
        while !cur.is_empty() {
            self.server_pdu_count = self.server_pdu_count.saturating_add(1);
            let n = self.server_pdu_count;
            let pdu_start = cur.pos();
            let pdu = match <GfxPdu as ironrdp_core::Decode>::decode(&mut cur) {
                Ok(p) => p,
                Err(e) => {
                    warn!(
                        "EGFX[{n}]: decode failed ({e}); {} bytes remaining",
                        cur.len()
                    );
                    break;
                }
            };
            let pdu_end = cur.pos();
            maybe_dump_pdu(n, &decompressed[pdu_start..pdu_end], &pdu);
            self.dispatch(n, &pdu, &mut out_messages);
        }
        Ok(out_messages)
    }
}

impl EgfxProcessor {
    /// Inspect a single decoded server PDU. Push any client-side reply
    /// (frame ack, etc.) into `out`.
    fn dispatch(&mut self, n: u64, pdu: &GfxPdu, out: &mut Vec<DvcMessage>) {
        match pdu {
            GfxPdu::CapabilitiesConfirm(c) => {
                self.capabilities_received = true;
                info!("EGFX[{n}]: CapabilitiesConfirm {:?}", c.0);
            }
            GfxPdu::ResetGraphics(p) => {
                info!(
                    "EGFX[{n}]: ResetGraphics width={} height={} monitors={}",
                    p.width,
                    p.height,
                    p.monitors.len()
                );
                self.surfaces.reset();
            }
            GfxPdu::CreateSurface(p) => {
                debug!(
                    "EGFX[{n}]: CreateSurface id={} {}x{} pixfmt={:?}",
                    p.surface_id, p.width, p.height, p.pixel_format
                );
                self.surfaces.create_surface(p);
            }
            GfxPdu::DeleteSurface(p) => {
                debug!("EGFX[{n}]: DeleteSurface id={}", p.surface_id);
                self.surfaces.delete_surface(p);
            }
            GfxPdu::MapSurfaceToOutput(p) => {
                debug!(
                    "EGFX[{n}]: MapSurfaceToOutput id={} ->({},{})",
                    p.surface_id, p.output_origin_x, p.output_origin_y
                );
                self.surfaces
                    .map_to_output(p.surface_id, p.output_origin_x as i32, p.output_origin_y as i32);
            }
            GfxPdu::StartFrame(p) => debug!(
                "EGFX[{n}]: StartFrame frame_id={} timestamp={:?}",
                p.frame_id, p.timestamp
            ),
            GfxPdu::EndFrame(p) => {
                self.total_frames_decoded = self.total_frames_decoded.saturating_add(1);
                debug!(
                    "EGFX[{n}]: EndFrame frame_id={} total_decoded={}",
                    p.frame_id, self.total_frames_decoded
                );
                self.flush_dirty_to_framebuffer();
                self.maybe_dump_surface(p.frame_id);
                let ack = FrameAcknowledgePdu {
                    queue_depth: QueueDepth::Unavailable, // FreeRDP-equivalent of "send the next frame"
                    frame_id: p.frame_id,
                    total_frames_decoded: self.total_frames_decoded,
                };
                out.push(Box::new(GfxClientMessage(GfxPdu::FrameAcknowledge(ack))));
            }
            GfxPdu::WireToSurface1(p) => self.handle_wire_to_surface1(n, p),
            GfxPdu::WireToSurface2(p) => self.handle_wire_to_surface2(n, p),
            GfxPdu::SolidFill(p) => {
                debug!(
                    "EGFX[{n}]: SolidFill surface={} rects={} colour={:?}",
                    p.surface_id,
                    p.rectangles.len(),
                    p.fill_pixel
                );
                self.surfaces.solid_fill(p);
            }
            GfxPdu::SurfaceToSurface(p) => {
                debug!(
                    "EGFX[{n}]: SurfaceToSurface src={} dst={} points={}",
                    p.source_surface_id,
                    p.destination_surface_id,
                    p.destination_points.len()
                );
                self.surfaces.surface_to_surface(p);
            }
            GfxPdu::SurfaceToCache(p) => {
                debug!(
                    "EGFX[{n}]: SurfaceToCache surface={} key=0x{:016x} cache_slot={}",
                    p.surface_id, p.cache_key, p.cache_slot
                );
                self.surfaces.surface_to_cache(p);
            }
            GfxPdu::CacheToSurface(p) => {
                debug!(
                    "EGFX[{n}]: CacheToSurface cache_slot={} surface={} positions={}",
                    p.cache_slot,
                    p.surface_id,
                    p.destination_points.len()
                );
                self.surfaces.cache_to_surface(p);
            }
            GfxPdu::EvictCacheEntry(p) => {
                debug!("EGFX[{n}]: EvictCacheEntry cache_slot={}", p.cache_slot);
                self.surfaces.evict_cache(p);
            }
            GfxPdu::DeleteEncodingContext(_) => debug!("EGFX[{n}]: DeleteEncodingContext"),
            GfxPdu::CacheImportReply(_) => debug!("EGFX[{n}]: CacheImportReply"),
            GfxPdu::MapSurfaceToScaledOutput(_) => {
                debug!("EGFX[{n}]: MapSurfaceToScaledOutput")
            }
            GfxPdu::MapSurfaceToScaledWindow(_) => {
                debug!("EGFX[{n}]: MapSurfaceToScaledWindow")
            }
            // Client-origin variants of the merged GfxPdu enum
            // (CapabilitiesAdvertise, FrameAcknowledge, CacheImportOffer,
            // QoeFrameAcknowledge, MapSurfaceToWindow): a server must not
            // send these; log and drop.
            other => warn!("EGFX[{n}]: unexpected client-origin PDU: {}", pdu_kind_label(other)),
        }
        if !self.capabilities_received {
            warn!("EGFX[{n}]: server PDU before CapabilitiesConfirm");
        }
    }
}

impl EgfxProcessor {
    /// Drain dirty rects from `SurfaceManager`, project each through the
    /// surface's `MapSurfaceToOutput` mapping, and copy the corresponding
    /// pixels from the surface (RGBA8888) into `SessionState.framebuffer`
    /// (BGRA in memory, i.e. Android `ARGB_8888` little-endian). Coalesces
    /// all rects into a single bounding-box `on_frame_update` call so the
    /// Kotlin/Compose side gets one repaint per frame instead of dozens.
    fn flush_dirty_to_framebuffer(&mut self) {
        let dirty = self.surfaces.take_dirty();
        if dirty.is_empty() {
            return;
        }
        // Project to host-output coords + collect (left, top, w, h) per
        // rect for the copy step. We do the lookups up-front so the
        // SessionState write lock is held only for the actual blit.
        struct ProjectedRect {
            surface_id: u16,
            // surface-local bounds (clipped to surface)
            sx: u32,
            sy: u32,
            w: u32,
            h: u32,
            // host-output bounds (after MapSurfaceToOutput translation)
            ox: i32,
            oy: i32,
        }
        let mut projected: Vec<ProjectedRect> = Vec::with_capacity(dirty.len());
        for (sid, r) in &dirty {
            let Some(surface) = self.surfaces.surface(*sid) else {
                continue;
            };
            let (sx, sy, w, h) = clip_to_surface(r, surface.width, surface.height);
            if w == 0 || h == 0 {
                continue;
            }
            let mapping = self.surfaces.output_for(*sid);
            let (ox, oy) = match mapping {
                Some(m) => (
                    m.output_origin_x + sx as i32,
                    m.output_origin_y + sy as i32,
                ),
                None => (sx as i32, sy as i32),
            };
            projected.push(ProjectedRect {
                surface_id: *sid,
                sx,
                sy,
                w,
                h,
                ox,
                oy,
            });
        }
        if projected.is_empty() {
            return;
        }

        // Bounding box across all rects (in output coords) for the callback.
        let mut bb_l = i32::MAX;
        let mut bb_t = i32::MAX;
        let mut bb_r = i32::MIN;
        let mut bb_b = i32::MIN;

        let state = self.state.clone();
        let frame_cb = {
            let mut s = match state.write() {
                Ok(s) => s,
                Err(_) => return,
            };
            let Some(fb) = s.framebuffer.as_mut() else {
                return;
            };
            let fb_w = fb.width as i32;
            let fb_h = fb.height as i32;
            for pr in &projected {
                let Some(surface) = self.surfaces.surface(pr.surface_id) else {
                    continue;
                };
                // Clip to framebuffer bounds.
                let dst_l = pr.ox.max(0);
                let dst_t = pr.oy.max(0);
                let dst_r = (pr.ox + pr.w as i32).min(fb_w);
                let dst_b = (pr.oy + pr.h as i32).min(fb_h);
                if dst_r <= dst_l || dst_b <= dst_t {
                    continue;
                }
                let copy_w = (dst_r - dst_l) as usize;
                let copy_h = (dst_b - dst_t) as usize;
                let src_x = (pr.sx as i32 + (dst_l - pr.ox)) as usize;
                let src_y = (pr.sy as i32 + (dst_t - pr.oy)) as usize;
                let src_stride = surface.width as usize * 4;
                let dst_stride = fb.width as usize * 4;
                for row in 0..copy_h {
                    let s_off = (src_y + row) * src_stride + src_x * 4;
                    let d_off = (dst_t as usize + row) * dst_stride + dst_l as usize * 4;
                    // Surface is RGBA8888 ([R,G,B,A] bytes). Android's
                    // ARGB_8888 framebuffer (copyPixelsFromBuffer) also expects
                    // RGBA byte order, so copy straight through — no R<->B swap
                    // (#212: the swap rendered blue as orange on-device).
                    let src_row = &surface.pixels[s_off..s_off + copy_w * 4];
                    let dst_row = &mut fb.pixels[d_off..d_off + copy_w * 4];
                    dst_row.copy_from_slice(src_row);
                }
                bb_l = bb_l.min(dst_l);
                bb_t = bb_t.min(dst_t);
                bb_r = bb_r.max(dst_r);
                bb_b = bb_b.max(dst_b);
            }
            s.frame_callback.clone()
        };
        if bb_r <= bb_l || bb_b <= bb_t {
            return;
        }
        debug!(
            "EGFX flush: {} dirty rect(s) -> bbox ({bb_l},{bb_t})-({bb_r},{bb_b})",
            dirty.len()
        );
        if let Some(cb) = frame_cb {
            cb.on_frame_update(
                bb_l as u16,
                bb_t as u16,
                (bb_r - bb_l) as u16,
                (bb_b - bb_t) as u16,
            );
        }
    }

    /// If `EGFX_DUMP_DIR` is set, write surface 0 as a PPM after each
    /// EndFrame. Useful for visual diff against a VNC reference shot from
    /// the host smoke driver — no extra image-crate dependency.
    fn maybe_dump_surface(&self, frame_id: u32) {
        let Ok(dir) = std::env::var("EGFX_DUMP_DIR") else {
            return;
        };
        let Some(s) = self.surfaces.surface(0) else {
            return;
        };
        let path = format!("{dir}/surface0_frame{frame_id:04}.ppm");
        let mut buf = format!("P6\n{} {}\n255\n", s.width, s.height).into_bytes();
        // Surface stores RGBA8888; PPM is RGB.
        buf.reserve(s.pixels.len() / 4 * 3);
        for px in s.pixels.chunks_exact(4) {
            buf.extend_from_slice(&px[..3]);
        }
        if let Err(e) = std::fs::write(&path, &buf) {
            warn!("EGFX surface dump to {path} failed: {e}");
        } else {
            info!("EGFX surface dumped to {path}");
        }
    }

    fn handle_wire_to_surface1(&mut self, n: u64, p: &WireToSurface1Pdu) {
        // MS-RDPEGFX `RDPGFX_RECT16` uses *exclusive* right/bottom for
        // WireToSurface destinations (matches FreeRDP's `width = right -
        // left`); egfx 0.2 types this correctly as ExclusiveRectangle, so
        // right/bottom are one-past-end and we use the width directly.
        let r = &p.destination_rectangle;
        let w = (r.right as i32 - r.left as i32).max(0) as u32;
        let h = (r.bottom as i32 - r.top as i32).max(0) as u32;
        debug!(
            "EGFX[{n}]: WireToSurface1 surface={} codec={:?} pf={:?} {}x{} @({},{}) {} bytes",
            p.surface_id,
            p.codec_id,
            p.pixel_format,
            w,
            h,
            r.left,
            r.top,
            p.bitmap_data.len()
        );
        if w == 0 || h == 0 {
            return;
        }
        match p.codec_id {
            Codec1Type::ClearCodec => {
                let tile = match self.clear_decoder.decompress(&p.bitmap_data, w, h) {
                    Ok(t) => t,
                    Err(e) => {
                        warn!(
                            "EGFX[{n}]: ClearCodec decompress failed: {e} ({w}x{h}, {} bytes)",
                            p.bitmap_data.len()
                        );
                        // For triage of future regressions, dumping the
                        // payload to /tmp under EGFX_DUMP_DIR matches the
                        // surface-dump convention.
                        if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
                            let path = format!("{dir}/clear_fail_{n}_{w}x{h}.bin");
                            let _ = std::fs::write(&path, &p.bitmap_data);
                        }
                        return;
                    }
                };
                let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                    warn!("EGFX[{n}]: WireToSurface1 unknown surface {}", p.surface_id);
                    return;
                };
                surface.blit_rgba(u32::from(r.left), u32::from(r.top), w, h, &tile);
                self.surfaces.dirty.push((p.surface_id, r.clone()));
            }
            Codec1Type::Planar => {
                let mut rgb = Vec::new();
                if let Err(e) = self.planar_decoder.decode_bitmap_stream_to_rgb24(
                    &p.bitmap_data,
                    &mut rgb,
                    w as usize,
                    h as usize,
                ) {
                    warn!(
                        "EGFX[{n}]: Planar decode failed: {e} ({w}x{h}, {} bytes)",
                        p.bitmap_data.len()
                    );
                    if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
                        let path = format!("{dir}/planar_fail_{n}_{w}x{h}.bin");
                        let _ = std::fs::write(&path, &p.bitmap_data);
                    }
                    return;
                }
                // rgb24 -> rgba for the surface blit
                let mut tile = Vec::with_capacity((w * h * 4) as usize);
                for px in rgb.chunks_exact(3) {
                    tile.extend_from_slice(&[px[0], px[1], px[2], 0xFF]);
                }
                let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                    warn!("EGFX[{n}]: WireToSurface1 unknown surface {}", p.surface_id);
                    return;
                };
                surface.blit_rgba(u32::from(r.left), u32::from(r.top), w, h, &tile);
                self.surfaces.dirty.push((p.surface_id, r.clone()));
            }
            Codec1Type::Avc420 => {
                // #425 slice 2: decode H.264/AVC420 via the host-registered
                // MediaCodec decoder (Rust owns no H.264 decoder). Capture dump
                // for triage is kept, gated on EGFX_DUMP_DIR.
                if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
                    let path = format!("{dir}/avc_{n}_{:?}_{w}x{h}.bin", p.codec_id);
                    let _ = std::fs::write(&path, &p.bitmap_data);
                }
                // Parse RFX_AVC420_BITMAP_STREAM: region rects + QUANT_QUALITY,
                // then the Annex-B H.264 access unit in `stream.data`.
                let mut cursor = ReadCursor::new(&p.bitmap_data);
                let stream = match Avc420BitmapStream::decode(&mut cursor) {
                    Ok(s) => s,
                    Err(e) => {
                        warn!("EGFX[{n}]: AVC420 bitmap-stream parse failed: {e} ({} bytes)", p.bitmap_data.len());
                        return;
                    }
                };
                let Some(decoder) = self.state.read().ok().and_then(|s| s.avc_decoder.clone()) else {
                    warn!("EGFX[{n}]: AVC420 tile but no decoder registered (set_avc_decoder) — dropping");
                    return;
                };
                // The H.264 frame is a full picture the size of the destination
                // rectangle; `stream.rectangles` are changed-region hints. KRDP
                // sends one full-frame region, so blit the whole decoded frame
                // to the destination. ponytail: multi-region partial blits
                // (Windows/AVC444) collapse to a full-dest repaint here — still
                // correct pixels, just not minimal; refine in slice 3.
                let rgba = decoder.decode(stream.data.to_vec(), w as u16, h as u16);
                let want = (w * h * 4) as usize;
                if rgba.len() < want {
                    warn!("EGFX[{n}]: AVC420 decoder returned {} bytes, need {} ({w}x{h}) — dropping", rgba.len(), want);
                    return;
                }
                let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                    warn!("EGFX[{n}]: WireToSurface1 unknown surface {}", p.surface_id);
                    return;
                };
                surface.blit_rgba(u32::from(r.left), u32::from(r.top), w, h, &rgba);
                self.surfaces.dirty.push((p.surface_id, r.clone()));
            }
            Codec1Type::Avc444 | Codec1Type::Avc444v2 => {
                // #425 slice 3: AVC444 dual-stream (4:2:0 luma + chroma aux)
                // → 4:4:4. Not decoded yet; dump for capture, then drop.
                info!(
                    "EGFX[{n}]: AVC444 tile codec={:?} {}x{} {} bytes (decode NYI — #425 slice 3)",
                    p.codec_id, w, h, p.bitmap_data.len()
                );
                if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
                    let path = format!("{dir}/avc_{n}_{:?}_{w}x{h}.bin", p.codec_id);
                    let _ = std::fs::write(&path, &p.bitmap_data);
                }
            }
            other => {
                debug!(
                    "EGFX[{n}]: WireToSurface1 codec {other:?} not yet handled ({} bytes ignored)",
                    p.bitmap_data.len()
                );
            }
        }
    }

    fn handle_wire_to_surface2(&mut self, n: u64, p: &WireToSurface2Pdu) {
        debug!(
            "EGFX[{n}]: WireToSurface2 surface={} codec={:?} ctx={} {} bytes",
            p.surface_id,
            p.codec_id,
            p.codec_context_id,
            p.bitmap_data.len()
        );
        if let Ok(dir) = std::env::var("EGFX_DUMP_DIR") {
            let path = format!(
                "{dir}/wts2_{n}_surface{}_ctx{}_codec{:?}.bin",
                p.surface_id, p.codec_context_id, p.codec_id
            );
            let _ = std::fs::write(&path, &p.bitmap_data);
        }
        match p.codec_id {
            Codec2Type::RemoteFxProgressive => {
                let mut tiles = Vec::new();
                if let Err(e) =
                    self.progressive_decoder
                        .decode(p.surface_id, &p.bitmap_data, &mut tiles)
                {
                    warn!(
                        "EGFX[{n}]: Progressive decode failed: {e} ({} bytes)",
                        p.bitmap_data.len()
                    );
                    return;
                }
                debug!(
                    "EGFX[{n}]: Progressive surface={} produced {} tile(s)",
                    p.surface_id,
                    tiles.len()
                );
                {
                    let Some(surface) = self.surfaces.surface_mut(p.surface_id) else {
                        warn!("EGFX[{n}]: WireToSurface2 unknown surface {}", p.surface_id);
                        return;
                    };
                    for tile in &tiles {
                        surface.blit_rgba(u32::from(tile.x), u32::from(tile.y), 64, 64, &tile.rgba);
                    }
                }
                for tile in &tiles {
                    self.surfaces.dirty.push((
                        p.surface_id,
                        ironrdp_pdu::geometry::ExclusiveRectangle {
                            left: tile.x,
                            top: tile.y,
                            right: tile.x.saturating_add(64),
                            bottom: tile.y.saturating_add(64),
                        },
                    ));
                }
            }
        }
    }
}

/// Clip an EGFX rectangle (RDPGFX_RECT16, exclusive right/bottom) to a
/// surface of the given size. Returns `(x, y, w, h)` in surface-local
/// pixels. `(0, 0, 0, 0)` if the rect is fully outside.
fn clip_to_surface(r: &ironrdp_pdu::geometry::ExclusiveRectangle, sw: u32, sh: u32) -> (u32, u32, u32, u32) {
    let l = u32::from(r.left).min(sw);
    let t = u32::from(r.top).min(sh);
    let right = u32::from(r.right).min(sw);
    let bottom = u32::from(r.bottom).min(sh);
    if right <= l || bottom <= t {
        (0, 0, 0, 0)
    } else {
        (l, t, right - l, bottom - t)
    }
}

impl DvcClientProcessor for EgfxProcessor {}

/// If `EGFX_PDU_DUMP_DIR` is set, write the post-zgfx-decompressed bytes
/// of each [`GfxPdu`] to `<dir>/pdu_NNNN_<kind>.bin`. Useful as
/// regression / upstream-bug-report fixtures: the bytes are exactly what
/// the `<GfxPdu as Decode>::decode` parser saw, so feeding them back
/// through the same parser is a deterministic reproduction.
///
/// Names are zero-padded so a normal `ls` lists them in arrival order.
/// Logs (not panics) on I/O failure — a session shouldn't die because
/// the brewer's dump dir is full.
fn maybe_dump_pdu(n: u64, bytes: &[u8], pdu: &GfxPdu) {
    let Ok(dir) = std::env::var("EGFX_PDU_DUMP_DIR") else {
        return;
    };
    let kind = pdu_kind_label(pdu);
    let path = format!("{dir}/pdu_{n:04}_{kind}.bin");
    if let Err(e) = std::fs::write(&path, bytes) {
        warn!("EGFX_PDU_DUMP write failed for {path}: {e}");
    }
}

fn pdu_kind_label(p: &GfxPdu) -> &'static str {
    // Exhaustive match — if upstream adds a GfxPdu variant we want
    // the build to break here so we add it to the dump filename.
    match p {
        GfxPdu::CapabilitiesConfirm(_) => "capabilities_confirm",
        GfxPdu::ResetGraphics(_) => "reset_graphics",
        GfxPdu::CreateSurface(_) => "create_surface",
        GfxPdu::DeleteSurface(_) => "delete_surface",
        GfxPdu::MapSurfaceToOutput(_) => "map_surface_to_output",
        GfxPdu::MapSurfaceToScaledOutput(_) => "map_surface_to_scaled_output",
        GfxPdu::MapSurfaceToScaledWindow(_) => "map_surface_to_scaled_window",
        GfxPdu::StartFrame(_) => "start_frame",
        GfxPdu::EndFrame(_) => "end_frame",
        GfxPdu::WireToSurface1(_) => "wire_to_surface1",
        GfxPdu::WireToSurface2(_) => "wire_to_surface2",
        GfxPdu::SolidFill(_) => "solid_fill",
        GfxPdu::SurfaceToSurface(_) => "surface_to_surface",
        GfxPdu::SurfaceToCache(_) => "surface_to_cache",
        GfxPdu::CacheToSurface(_) => "cache_to_surface",
        GfxPdu::EvictCacheEntry(_) => "evict_cache_entry",
        GfxPdu::DeleteEncodingContext(_) => "delete_encoding_context",
        GfxPdu::CacheImportReply(_) => "cache_import_reply",
        // Client-origin variants (the merged GfxPdu enum covers both
        // directions); a server never legitimately sends these.
        _ => "unexpected_client_origin_pdu",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// #425: verify the RFX_AVC420_BITMAP_STREAM header parse matches the wire
    /// format observed in real KRDP captures — 1 full-frame region (0,0,1280,
    /// 800), QUANT_QUALITY, then the Annex-B H.264 access unit. The AVC420 arm
    /// relies on `stream.data` being exactly the trailing Annex-B bytes (it
    /// forwards them to the MediaCodec decoder), so this pins that contract.
    #[test]
    fn avc420_bitmap_stream_parse() {
        // Header bytes lifted verbatim from a real KRDP frame-1 capture.
        let mut buf: Vec<u8> = vec![
            0x01, 0x00, 0x00, 0x00, // numRegionRects = 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x20, 0x03, // RECT16 l=0 t=0 r=1280 b=800
            0x16, 0x64, // QUANT_QUALITY: quant=22 quality=100
        ];
        // Annex-B start code + SPS NAL header (0x67) + a couple payload bytes.
        let annex_b = [0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0];
        buf.extend_from_slice(&annex_b);

        let stream = Avc420BitmapStream::decode(&mut ReadCursor::new(&buf))
            .expect("AVC420 header should parse");
        assert_eq!(stream.rectangles.len(), 1, "one region rect");
        assert_eq!(stream.quant_qual_vals.len(), 1, "one quant/quality pair");
        assert_eq!(stream.data, &annex_b, "data is the trailing Annex-B AU");
    }
}
