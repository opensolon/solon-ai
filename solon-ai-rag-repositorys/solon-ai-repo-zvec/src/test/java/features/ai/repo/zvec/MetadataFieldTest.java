package features.ai.repo.zvec;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.zvec.MetadataField;
import org.zvec.binding.DataType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 元数据字段定义测试
 *
 * @author noear
 */
public class MetadataFieldTest {
    @Test
    public void case1_factories() {
        assertEquals(DataType.STRING, MetadataField.text("f").getDataType());
        assertEquals(DataType.BOOL, MetadataField.bool("f").getDataType());
        assertEquals(DataType.INT32, MetadataField.int32("f").getDataType());
        assertEquals(DataType.INT64, MetadataField.int64("f").getDataType());
        assertEquals(DataType.FLOAT, MetadataField.floatField("f").getDataType());
        assertEquals(DataType.DOUBLE, MetadataField.doubleField("f").getDataType());
    }

    @Test
    public void case2_name() {
        assertEquals("category", MetadataField.text("category").getName());
    }

    @Test
    public void case3_indexedDefaultTrue() {
        assertTrue(MetadataField.text("f").isIndexed());
    }

    @Test
    public void case4_indexedFalse() {
        MetadataField field = MetadataField.text("f").indexed(false);
        assertFalse(field.isIndexed());
        //链式调用返回自身，便于继续配置
        assertEquals("f", field.getName());
    }

    @Test
    public void case5_customType() {
        MetadataField field = new MetadataField("f", DataType.UINT32);
        assertEquals(DataType.UINT32, field.getDataType());
    }
}
