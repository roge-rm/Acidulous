package com.rm.acidulous.res

import org.junit.Assert.assertEquals
import org.junit.Test

/** France's spacing, put into the Canadian French as it's read. */
class FrenchTypographyTest {
    private val nb = ' '
    private val narrow = ' '

    @Test
    fun narrowSpaceBeforeQuestionExclamationAndSemicolon() {
        assertEquals("Vider ce clip$narrow?", FrenchTypography.forFrance("Vider ce clip?"))
        assertEquals("doux$narrow; rapide", FrenchTypography.forFrance("doux; rapide"))
        assertEquals("Fini$narrow!", FrenchTypography.forFrance("Fini!"))
        assertEquals("Supprimer «${nb}x$nb»$narrow?", FrenchTypography.forFrance("Supprimer «${nb}x$nb»?"))
    }

    @Test
    fun colonsKeepOrGainANoBreakSpace() {
        assertEquals("langue$nb: liste", FrenchTypography.forFrance("langue$nb: liste"))
        assertEquals("langue$nb: liste", FrenchTypography.forFrance("langue : liste"))
    }

    @Test
    fun timesAddressesAndCodeAreLeftAlone() {
        assertEquals("14:05", FrenchTypography.forFrance("14:05"))
        assertEquals("https://example.org", FrenchTypography.forFrance("https://example.org"))
        assertEquals("`a ? b : c`", FrenchTypography.forFrance("`a ? b : c`"))
    }

    @Test
    fun aPairOfMarksGetsOneSpace() {
        assertEquals("Quoi$narrow?!", FrenchTypography.forFrance("Quoi?!"))
    }
}
