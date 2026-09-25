package com.rm.acidulous.desktop

import com.rm.acidulous.AudioInput
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopAudioTest {
    @Test
    fun kindsFromPulseAudioNames() {
        assertEquals(AudioInput.Kind.Usb, kindOf("Interface USB Analog Stereo", "alsa_input.usb-Maker_Interface_USB-00.analog-stereo"))
        assertEquals(AudioInput.Kind.BuiltIn, kindOf("Built-in Audio Analog Stereo", "alsa_input.pci-0000_00_1f.3.analog-stereo"))
        assertEquals(AudioInput.Kind.Bluetooth, kindOf("Headphones", "bluez_input.00_11_22_33_44_55.0"))
        assertEquals(AudioInput.Kind.NotAnEar, kindOf("Monitor of Built-in Audio Analog Stereo", "alsa_output.pci-0000_00_1f.3.analog-stereo.monitor"))
        assertEquals(AudioInput.Kind.Other, kindOf("Loopback", "hw:2,0"))
    }

    @Test
    fun namesAloneWhereTheServerGivesNone() {
        assertEquals(AudioInput.Kind.Usb, kindOf("USB Audio Device", ""))
        assertEquals(AudioInput.Kind.NotAnEar, kindOf("Monitor of Dummy Output", ""))
    }
}
