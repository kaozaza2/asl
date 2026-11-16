package dev.asl.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantsTest {
    @Test
    fun distroDeviceMapsIndexToLetter() {
        assertEquals("/dev/vda", distroDevice(0))
        assertEquals("/dev/vdb", distroDevice(1))
        assertEquals("/dev/vdz", distroDevice(25))
    }

    @Test(expected = IllegalArgumentException::class)
    fun distroDeviceRejectsOutOfRange() {
        distroDevice(26)
    }

    @Test
    fun distroNameValidation() {
        assertTrue(DISTRO_NAME.matches("Ubuntu"))
        assertTrue(DISTRO_NAME.matches("my-distro_1.0"))
        assertFalse(DISTRO_NAME.matches(""))
        assertFalse(DISTRO_NAME.matches("bad name"))
        assertFalse(DISTRO_NAME.matches("a".repeat(65)))
    }
}