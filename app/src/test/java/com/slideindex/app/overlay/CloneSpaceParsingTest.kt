package com.slideindex.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneSpaceParsingTest {

    @Test
    fun parseUserIds_readsFlymeOutput_andExcludesCurrentUser() {
        val output = """
            Users:
            	UserInfo{0:机主:4c13} running
            	UserInfo{999:FlymeParallelSpace:1010} running
        """.trimIndent()

        assertEquals(listOf(999), CloneSpaceParsing.parseUserIds(output, currentUserId = 0))
    }

    @Test
    fun parseUserIds_dedupesAndKeepsOrder() {
        val output = """
            UserInfo{0:Owner:0}
            UserInfo{10:Work:0}
            UserInfo{999:Clone:0}
            UserInfo{10:Work:0}
        """.trimIndent()

        assertEquals(listOf(10, 999), CloneSpaceParsing.parseUserIds(output, currentUserId = 0))
    }

    @Test
    fun parseUserIds_ignoresMalformedLines() {
        val output = "Users:\n  UserInfo{abc:Broken:0}\n  UserInfo{11:Guest:0}\n"

        assertEquals(listOf(11), CloneSpaceParsing.parseUserIds(output, currentUserId = 0))
    }

    @Test
    fun parsePackageNames_filtersNonPackageLines() {
        val output = """
            package:com.ss.android.ugc.aweme
            package:com.tencent.mm

            Warning: something
              package:com.example.app
        """.trimIndent()

        assertEquals(
            listOf("com.ss.android.ugc.aweme", "com.tencent.mm", "com.example.app"),
            CloneSpaceParsing.parsePackageNames(output).toList(),
        )
    }

    @Test
    fun parsePackageNames_handlesEmptyOutput() {
        assertTrue(CloneSpaceParsing.parsePackageNames("").isEmpty())
        assertTrue(CloneSpaceParsing.parsePackageNames("No packages.").isEmpty())
    }

    @Test
    fun parseUserTypes_mapsUserToType() {
        val output = """
            UserInfo{0:机主:4c13} serialNo=0 isPrimary=true
              Type: android.os.usertype.full.SYSTEM
            UserInfo{999:FlymeParallelSpace:1010} serialNo=10 isPrimary=false
              Type: android.os.usertype.profile.CLONE
        """.trimIndent()

        assertEquals(
            mapOf(
                0 to "android.os.usertype.full.SYSTEM",
                999 to "android.os.usertype.profile.CLONE",
            ),
            CloneSpaceParsing.parseUserTypes(output),
        )
    }

    @Test
    fun parseUserTypes_ignoresTypeWithoutUserBlock() {
        val output = """
            Type: android.os.usertype.full.SYSTEM
            UserInfo{10:Work:0} serialNo=1
        """.trimIndent()

        assertTrue(CloneSpaceParsing.parseUserTypes(output).isEmpty())
    }
}
