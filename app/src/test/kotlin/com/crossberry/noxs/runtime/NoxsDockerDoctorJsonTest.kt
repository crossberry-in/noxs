/*
 * Noxs — original implementation.
 * Tests for the `nx docker doctor --json` parser (NoxsDockerDoctorJson).
 * Fixtures mirror the real docker-lib.sh output shape byte for byte.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoxsDockerDoctorJsonTest {

    @Test
    fun `parses a healthy doctor report`() {
        val json = """{"rootfs":"ok","cli":"installed","daemon":"running","mode":"compatibility",""" +
            """"storage":"overlay2","dns":"ok","registry":"reachable","buildkit":"available",""" +
            """"kernel":"ok","arch":"aarch64"}"""
        val report = NoxsDockerDoctorJson.parse(json)
        assertTrue(report.valid)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.rootfs)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.cli)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.daemon)
        assertEquals("compatibility", report.mode)
        assertEquals("overlay2", report.storage)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.dns)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.registry)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.buildkit)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.kernel)
        assertEquals("aarch64", report.arch)
    }

    @Test
    fun `vfs storage is a limitation not a failure`() {
        val json = """{"rootfs":"ok","cli":"installed","daemon":"running","mode":"compatibility",""" +
            """"storage":"vfs","dns":"ok","registry":"reachable","buildkit":"missing",""" +
            """"kernel":"limited","arch":"aarch64"}"""
        val report = NoxsDockerDoctorJson.parse(json)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.rootfs)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.daemon)
        // buildkit missing + kernel limited both map to WARN rows in the UI
        assertEquals(NoxsDockerDoctorJson.Status.WARN, report.buildkit)
        assertEquals(NoxsDockerDoctorJson.Status.WARN, report.kernel)
    }

    @Test
    fun `broken rootfs and failed dns map to FAIL`() {
        val json = """{"rootfs":"broken","cli":"installed","daemon":"stopped","mode":"compatibility",""" +
            """"storage":"","dns":"fail","registry":"unreachable","buildkit":"missing",""" +
            """"kernel":"ok","arch":"aarch64"}"""
        val report = NoxsDockerDoctorJson.parse(json)
        assertEquals(NoxsDockerDoctorJson.Status.FAIL, report.rootfs)
        assertEquals(NoxsDockerDoctorJson.Status.FAIL, report.dns)
        // daemon stopped + registry unreachable stay honest WARNs (limitations)
        assertEquals(NoxsDockerDoctorJson.Status.WARN, report.daemon)
        assertEquals(NoxsDockerDoctorJson.Status.WARN, report.registry)
    }

    @Test
    fun `missing docker cli degrades to WARN not fake OK`() {
        val json = """{"rootfs":"ok","cli":"missing","daemon":"not_installed","mode":"",""" +
            """"storage":"","dns":"ok","registry":"reachable","buildkit":"missing",""" +
            """"kernel":"ok","arch":"aarch64"}"""
        val report = NoxsDockerDoctorJson.parse(json)
        assertEquals(NoxsDockerDoctorJson.Status.WARN, report.cli)
        assertEquals(NoxsDockerDoctorJson.Status.WARN, report.daemon)
    }

    @Test
    fun `garbage output yields all-unknown report that is not valid`() {
        val report = NoxsDockerDoctorJson.parse("bash: nx: command not found")
        assertFalse(report.valid)
        assertEquals(NoxsDockerDoctorJson.Status.UNKNOWN, report.rootfs)
        assertEquals(NoxsDockerDoctorJson.Status.UNKNOWN, report.daemon)
        assertEquals("unknown", report.arch)
    }

    @Test
    fun `empty output yields an unknown report`() {
        val report = NoxsDockerDoctorJson.parse("")
        assertFalse(report.valid)
    }

    @Test
    fun `json embedded in noisy output still parses (extracts the object)`() {
        val noisy = "some stray line\n{\"rootfs\":\"ok\",\"cli\":\"missing\"}\ntrailing noise"
        val report = NoxsDockerDoctorJson.parse(noisy)
        assertEquals(NoxsDockerDoctorJson.Status.OK, report.rootfs)
    }
}
