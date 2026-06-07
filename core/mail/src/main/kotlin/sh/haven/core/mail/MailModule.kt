package sh.haven.core.mail

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the v1 Proton engine as the [MailClient]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MailModule {
    @Binds
    @Singleton
    abstract fun bindMailClient(impl: ProtonMailClient): MailClient
}
