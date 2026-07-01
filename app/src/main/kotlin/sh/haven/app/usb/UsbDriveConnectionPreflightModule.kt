package sh.haven.app.usb

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import sh.haven.core.data.repository.ConnectionPreflight

/** Binds the app-layer USB-drive-bookmark preflight to the `core/data` interface [feature/connections] depends on. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UsbDriveConnectionPreflightModule {
    @Binds
    abstract fun bindConnectionPreflight(impl: UsbDriveConnectionPreflight): ConnectionPreflight
}
