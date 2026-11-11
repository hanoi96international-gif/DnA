package de.ayutv.data.translate

import de.ayutv.data.translate.OnDeviceTranslator.Companion.route
import de.ayutv.data.translate.OnDeviceTranslator.Hop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationRouteTest {
    @Test
    fun directPair() = assertEquals(listOf(Hop("en-de")), route("en", "de"))

    @Test
    fun sameLanguageNeedsNothing() = assertEquals(emptyList<Hop>(), route("de", "de"))

    @Test
    fun pivotOverEnglish() = assertEquals(listOf(Hop("ko-en"), Hop("en-de")), route("ko", "de"))

    @Test
    fun multilingualTargetUsesLanguageToken() = assertEquals(listOf(Hop("en-mul", ">>pol<<")), route("en", "pl"))

    @Test
    fun romanceTarget() = assertEquals(listOf(Hop("en-ROMANCE", ">>pt<<")), route("en", "pt"))

    @Test
    fun unknownSourceGoesThroughMultilingualModel() = assertEquals(listOf(Hop("mul-en"), Hop("en-de")), route("el", "de"))

    @Test
    fun unsupportedTarget() = assertNull(route("en", "ko"))
}
