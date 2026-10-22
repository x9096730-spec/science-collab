/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.elementx

import im.vector.app.core.resources.StringProvider
import im.vector.app.features.raw.wellknown.ElementWellKnown

/**
 * Application id of the Element X FOSS app, used when the homeserver does not specify a target.
 */
const val DEFAULT_TARGET_APP_ID = "io.element.android.x"

sealed interface MigrationBannerState {
    data object Hide : MigrationBannerState
    data class Show(
            val title: String,
            val body: String,
            val showButton: Boolean,
            val buttonText: String,
            val targetAppId: String,
    ) : MigrationBannerState {
        val isElementX: Boolean
            get() = targetAppId == DEFAULT_TARGET_APP_ID
    }
}

/**
 * Science Collab: the "Download Element X" banner is permanently disabled.
 */
@Suppress("UNUSED_PARAMETER")
fun ElementWellKnown?.toMigrationBannerState(
        stringProvider: StringProvider,
        useDefaultOnMissingData: Boolean,
): MigrationBannerState {
    return MigrationBannerState.Hide
}
