package com.bustramnantes.app.presentation.common.utils

import com.bustramnantes.app.domain.model.DataResult
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensures raw network exceptions become the friendly connectivity message, not technical text. */
class ErrorMessagesTest {

    @Test
    fun `unknown host is a connectivity error`() {
        assertTrue(UnknownHostException("Unable to resolve host \"api.okina.fr\"").isConnectivityError())
    }

    @Test
    fun `wrapped io exception is a connectivity error`() {
        val wrapped = RuntimeException("boom", IOException("timeout"))
        assertTrue(wrapped.isConnectivityError())
    }

    @Test
    fun `serialization error is not a connectivity error`() {
        assertFalse(SerializationException("bad json").isConnectivityError())
    }

    @Test
    fun `error maps host failure to the friendly connectivity message`() {
        val error = DataResult.Error(
            UnknownHostException("Unable to resolve host \"api.okina.fr\""),
            message = "Unable to resolve host \"api.okina.fr\"",
        )
        assertEquals(CONNECTIVITY_ERROR_MESSAGE, error.toUserMessage("fallback"))
    }

    @Test
    fun `error maps non-connectivity failure to the caller fallback (never raw text)`() {
        val error = DataResult.Error(SerializationException("bad json"), message = "bad json")
        assertEquals("Impossible de récupérer les horaires.", error.toUserMessage("Impossible de récupérer les horaires."))
    }
}
