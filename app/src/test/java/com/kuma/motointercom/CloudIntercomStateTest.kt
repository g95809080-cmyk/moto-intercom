package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudIntercomStateTest {

    @Test
    fun activeStatesOwnTheCloudSession() {
        assertTrue(CloudIntercomState.Preparing("peer", "车友").ownsCloudSession)
        assertTrue(CloudIntercomState.JoiningRoom("车友").ownsCloudSession)
        assertTrue(CloudIntercomState.WaitingForPeer("车友").ownsCloudSession)
        assertTrue(CloudIntercomState.Connected("车友", 1).ownsCloudSession)
        assertTrue(CloudIntercomState.Reconnecting("车友").ownsCloudSession)
        assertTrue(CloudIntercomState.Stopping("车友").ownsCloudSession)
    }

    @Test
    fun idleAndFailedStatesReleaseTheCloudSession() {
        assertFalse(CloudIntercomState.Idle.ownsCloudSession)
        assertFalse(CloudIntercomState.Failed("车友", "进房失败").ownsCloudSession)
    }

    @Test
    fun activeStatusKeepsThePeerNameVisible() {
        assertEquals("等待 车友 进入云端房间…", CloudIntercomState.WaitingForPeer("车友").statusText())
        assertEquals("云端对讲中 · 车友", CloudIntercomState.Connected("车友", 1).statusText())
    }

    @Test
    fun stoppingDisablesTheCloudAction() {
        val state = CloudIntercomState.Stopping("车友")
        assertFalse(state.actionEnabled)
        assertEquals("正在结束…", state.actionLabel())
    }
}
