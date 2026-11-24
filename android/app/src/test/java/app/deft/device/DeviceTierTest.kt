package app.deft.device

import app.deft.config.Palette
import app.deft.config.ThemeSettings
import app.deft.ui.theme.DeftTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTierTest {
    private val gib = DeviceTierClassifier.GIB

    private fun signals(
        lowRam: Boolean = false,
        ram: Long = 8 * gib,
        cores: Int = 8,
        freqKhz: Long = 2_800_000,
        sdk: Int = 34,
    ) = DeviceSignals(lowRam, ram, cores, freqKhz, sdk)

    @Test
    fun androidLowRamFlagIsLowWhateverElseItSays() {
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.classify(signals(lowRam = true)))
    }

    @Test
    fun ramBelowFiveGibIsLow() {
        // A "4 GB" phone reports about 3.7 GiB; a "6 GB" phone reports about 5.6 GiB.
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.classify(signals(ram = (3.7 * gib).toLong())))
        assertEquals(DeviceTier.MID, DeviceTierClassifier.classify(signals(ram = (5.6 * gib).toLong(), cores = 8)))
    }

    @Test
    fun ramAtExactlyFiveGibIsNotLow() {
        assertEquals(DeviceTier.MID, DeviceTierClassifier.classify(signals(ram = 5 * gib, cores = 4, freqKhz = 2_000_000)))
    }

    @Test
    fun highNeedsRamCoresClockAndAndroid12() {
        assertEquals(DeviceTier.HIGH, DeviceTierClassifier.classify(signals()))
        assertEquals(DeviceTier.MID, DeviceTierClassifier.classify(signals(ram = 7 * gib - 1)))
        assertEquals(DeviceTier.MID, DeviceTierClassifier.classify(signals(cores = 6)))
        assertEquals(DeviceTier.MID, DeviceTierClassifier.classify(signals(freqKhz = 2_199_999)))
        assertEquals(DeviceTier.HIGH, DeviceTierClassifier.classify(signals(freqKhz = 2_200_000)))
        assertEquals(DeviceTier.MID, DeviceTierClassifier.classify(signals(sdk = 30)))
    }

    @Test
    fun unknownClockKeepsDeviceOffHigh() {
        assertEquals(DeviceTier.MID, DeviceTierClassifier.classify(signals(freqKhz = 0)))
    }

    @Test
    fun oldSdkIsLowBecauseOfBlurCost() {
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.classify(signals(sdk = 28)))
        assertEquals(DeviceTier.MID, DeviceTierClassifier.classify(signals(sdk = 29, ram = 6 * gib, cores = 8)))
    }

    @Test
    fun probeDropsOneTierOnlyWhenFramesAreSlow() {
        assertEquals(DeviceTier.HIGH, DeviceTierClassifier.applyProbe(DeviceTier.HIGH, 16.7))
        assertEquals(DeviceTier.HIGH, DeviceTierClassifier.applyProbe(DeviceTier.HIGH, 24.0))
        assertEquals(DeviceTier.MID, DeviceTierClassifier.applyProbe(DeviceTier.HIGH, 24.1))
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.applyProbe(DeviceTier.MID, 40.0))
        assertEquals(DeviceTier.LOW, DeviceTierClassifier.applyProbe(DeviceTier.LOW, 40.0))
    }

    @Test
    fun inconclusiveProbeKeepsStaticTier() {
        assertEquals(DeviceTier.HIGH, DeviceTierClassifier.applyProbe(DeviceTier.HIGH, 0.0))
    }

    @Test
    fun storedNameParsesCaseInsensitivelyAndRejectsJunk() {
        assertEquals(DeviceTier.MID, DeviceTier.parse("mid"))
        assertEquals(DeviceTier.HIGH, DeviceTier.parse("HIGH"))
        assertEquals(null, DeviceTier.parse("ultra"))
        assertEquals(null, DeviceTier.parse(null))
    }

    @Test
    fun midAndHighPublishBlurAndTheTierProperty() {
        val mid = Palette.css(Palette.resolve(ThemeSettings(DeftTokens.DEFAULT_ACCENT, "dark"), null, true, DeviceTier.MID))
        assertTrue(mid.contains("--deft-tier: mid;"))
        assertTrue(mid.contains("--deft-material-blur: blur(24px) saturate(1.7);"))
        assertTrue(mid.contains("--deft-material-bar: color-mix(in oklch, var(--deft-bg-elevated) 62%, transparent);"))
    }

    @Test
    fun lowTierOrReduceTransparencyPublishesOpaqueMaterial() {
        val low = Palette.css(Palette.resolve(ThemeSettings(DeftTokens.DEFAULT_ACCENT, "dark"), null, true, DeviceTier.LOW))
        assertTrue(low.contains("--deft-tier: low;"))
        assertTrue(low.contains("--deft-material-blur: none;"))
        assertTrue(low.contains("--deft-material-bar: color-mix(in oklch, var(--deft-bg-elevated) 92%, transparent);"))

        val reduced = Palette.css(
            Palette.resolve(ThemeSettings(DeftTokens.DEFAULT_ACCENT, "dark", reduceTransparency = true), null, true, DeviceTier.HIGH),
        )
        assertTrue(reduced.contains("--deft-tier: high;"))
        assertTrue(reduced.contains("--deft-material-blur: none;"))
        assertFalse(reduced.contains("blur(24px)"))
    }
}
