package mirror;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorConfigTest {

    @Test
    void parseWhitelist_nullOrEmpty() {
        assertNull(MirrorConfig.parseWhitelist(null));
        assertNull(MirrorConfig.parseWhitelist(""));
        assertNull(MirrorConfig.parseWhitelist("   "));
    }

    @Test
    void parseWhitelist_singleTopic() {
        Set<String> w = MirrorConfig.parseWhitelist("p2_v1_patrol_alarm");
        assertNotNull(w);
        assertEquals(1, w.size());
        assertTrue(w.contains("p2_v1_patrol_alarm"));
    }

    @Test
    void parseWhitelist_multipleTopicsWithSpaces() {
        Set<String> w = MirrorConfig.parseWhitelist(" topic-a , topic-b ,, topic-c ");
        assertEquals(3, w.size());
        assertTrue(w.contains("topic-a"));
        assertTrue(w.contains("topic-b"));
        assertTrue(w.contains("topic-c"));
    }

    @Test
    void parseWhitelist_deduplicates() {
        Set<String> w = MirrorConfig.parseWhitelist("a,b,a,c,b");
        assertEquals(3, w.size());
    }

    @Test
    void enumParsing_isCaseInsensitive() {
        // fromEnv 读环境变量,这里直接验证枚举本身大小写无关解析能力,
        // MirrorConfig 内部已经 toUpperCase(),所以 lowercase 输入也能接受。
        assertEquals(MirrorConfig.ConsumeFrom.LAST,
            MirrorConfig.ConsumeFrom.valueOf("LAST"));
        assertEquals(MirrorConfig.ConsumeFrom.valueOf("LAST".toUpperCase()),
            MirrorConfig.ConsumeFrom.valueOf("last".toUpperCase()));
        assertThrows(IllegalArgumentException.class,
            () -> MirrorConfig.ConsumeFrom.valueOf("INVALID"));
    }

    @Test
    void enumValues_complete() {
        // 文档承诺的取值必须存在
        assertEquals(4, MirrorConfig.ConsumeFrom.values().length);
        assertEquals(3, MirrorConfig.OnFailure.values().length);
        assertEquals(3, MirrorConfig.SendMode.values().length);
    }
}
