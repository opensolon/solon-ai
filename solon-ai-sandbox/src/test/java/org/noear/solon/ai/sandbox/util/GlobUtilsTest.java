package org.noear.solon.ai.sandbox.util;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class GlobUtilsTest {

    // --- containsGlobChars ---

    @Test
    void containsGlobChars_plainString_returnsFalse() {
        assertFalse(GlobUtils.containsGlobChars("hello"));
    }

    @Test
    void containsGlobChars_star_returnsTrue() {
        assertTrue(GlobUtils.containsGlobChars("*.txt"));
    }

    @Test
    void containsGlobChars_brackets_returnsTrue() {
        assertTrue(GlobUtils.containsGlobChars("file[0-9]"));
    }

    @Test
    void containsGlobChars_doubleStar_returnsTrue() {
        assertTrue(GlobUtils.containsGlobChars("path/**"));
    }

    @Test
    void containsGlobChars_questionMark_returnsTrue() {
        assertTrue(GlobUtils.containsGlobChars("file?.log"));
    }

    // --- removeTrailingGlobSuffix ---

    @Test
    void removeTrailingGlobSuffix_trailingDoubleStar() {
        assertEquals("path", GlobUtils.removeTrailingGlobSuffix("path/**"));
    }

    @Test
    void removeTrailingGlobSuffix_trailingDoubleStarSlash() {
        // The regex only strips /** at end, not /**/
        assertEquals("path/**/", GlobUtils.removeTrailingGlobSuffix("path/**/"));
    }

    @Test
    void removeTrailingGlobSuffix_noTrailingStar() {
        assertEquals("path/", GlobUtils.removeTrailingGlobSuffix("path/"));
    }

    @Test
    void removeTrailingGlobSuffix_plainPath() {
        assertEquals("path", GlobUtils.removeTrailingGlobSuffix("path"));
    }

    @Test
    void removeTrailingGlobSuffix_onlyGlob_returnsSlash() {
        assertEquals("/", GlobUtils.removeTrailingGlobSuffix("/**"));
    }

    // --- globToRegex ---

    @Test
    void globToRegex_starDotTxt() {
        String regex = GlobUtils.globToRegex("*.txt");
        assertEquals("^[^/]*\\.txt$", regex);
        Pattern p = Pattern.compile(regex);
        assertTrue(p.matcher("file.txt").matches());
        assertTrue(p.matcher("anything.txt").matches());
        assertFalse(p.matcher("dir/file.txt").matches());
        assertFalse(p.matcher("file.txt.bak").matches());
    }

    @Test
    void globToRegex_pathDoubleStar() {
        String regex = GlobUtils.globToRegex("path/**");
        // ** without preceding / is treated as plain globstar (matches anything)
        assertEquals("^path/.*$", regex);
        Pattern p = Pattern.compile(regex);
        assertTrue(p.matcher("path/foo").matches());
        assertTrue(p.matcher("path/foo/bar").matches());
        assertFalse(p.matcher("path").matches()); // needs at least path/
        assertFalse(p.matcher("other").matches());
    }

    @Test
    void globToRegex_questionMark() {
        String regex = GlobUtils.globToRegex("file?.log");
        assertEquals("^file[^/]\\.log$", regex);
        Pattern p = Pattern.compile(regex);
        assertTrue(p.matcher("fileA.log").matches());
        assertFalse(p.matcher("file.log").matches());
        assertFalse(p.matcher("fileAB.log").matches());
    }

    @Test
    void globToRegex_doubleStarSlash() {
        String regex = GlobUtils.globToRegex("src/**/*.java");
        assertEquals("^src/(.*/)?[^/]*\\.java$", regex);
        Pattern p = Pattern.compile(regex);
        assertTrue(p.matcher("src/Foo.java").matches());
        assertTrue(p.matcher("src/sub/Foo.java").matches());
        assertTrue(p.matcher("src/a/b/Foo.java").matches());
        assertFalse(p.matcher("other/Foo.java").matches());
    }

    @Test
    void globToRegex_characterClass() {
        String regex = GlobUtils.globToRegex("file[abc].txt");
        assertEquals("^file[abc]\\.txt$", regex);
        Pattern p = Pattern.compile(regex);
        assertTrue(p.matcher("filea.txt").matches());
        assertTrue(p.matcher("fileb.txt").matches());
        assertFalse(p.matcher("filed.txt").matches());
    }

    @Test
    void globToRegex_plainText() {
        String regex = GlobUtils.globToRegex("plain.txt");
        assertEquals("^plain\\.txt$", regex);
        Pattern p = Pattern.compile(regex);
        assertTrue(p.matcher("plain.txt").matches());
        assertFalse(p.matcher("other.txt").matches());
    }
}
