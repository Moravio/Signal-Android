
/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc.fhe

import android.content.res.AssetManager

object FHEService {
  init {
    System.loadLibrary("FHE")
  }

  @JvmStatic() external fun loadKeys(assets: AssetManager)

  @JvmStatic external fun createCryptoContext(outDirJ: String)

  @JvmStatic external fun encrypt(inputData: FloatArray): ByteArray

  @JvmStatic external fun decrypt(inputData: ByteArray): FloatArray
}