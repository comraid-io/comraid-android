package com.comraid.android.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Hand-written [PushPayload.reservedKeys] must equal the vendored contract key names (ADR 0017).
 * No codegen — equality against `contracts/push-payload/contract.json` only.
 */
class ReservedKeysContractTest {
  @Test
  fun handWrittenReservedSetEqualsContractKeyNames() {
    val contract = JSONObject(contractFile().readText())
    val keys = contract.getJSONArray("keys")
    val fromContract = buildSet {
      for (i in 0 until keys.length()) {
        add(keys.getJSONObject(i).getString("name"))
      }
    }
    assertEquals(
      "reserved set drifted from vendored contract.json — run scripts/sync-push-payload-contract.sh",
      fromContract,
      PushPayload.reservedKeys,
    )
  }

  private fun contractFile(): File {
    val dir = locateDir("contracts/push-payload", "../contracts/push-payload")
    val file = File(dir, "contract.json")
    require(file.isFile) { "missing vendored contract.json under $dir" }
    return file
  }

  private fun locateDir(vararg candidates: String): File =
    candidates.map(::File).firstOrNull { it.isDirectory }
      ?: error("could not locate dir ${candidates.joinToString()} from ${System.getProperty("user.dir")}")
}
