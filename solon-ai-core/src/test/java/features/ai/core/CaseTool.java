package features.ai.core;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.annotation.Param;

import java.util.Arrays;
import java.util.List;

/**
 * @author noear 2025/6/11 created
 */
public class CaseTool {
    @ToolMapping(description = "查询案件列表 建议最多查询5条")
    public List<String> getCaseList5(
            @Param(description = "查询条件") CaseBo caseBo,
            @Param(description = "页码") Integer pageNum,
            @Param(description = "每页条数") Integer pageSize
    ) { // @Header("X-User-Id") Long userId
        System.out.println("------------------查询案件列表");
////        caseBo.setInspectorId(userId);
//        caseBo.setCaseFiled(true);
//        // 一行代码，实现一个用户检索接口（MapUtils.flat 只是收集前端的请求参数）
//        PageQuery pageQuery = new PageQuery();
//        pageQuery.setPageNum(pageNum);
//        pageQuery.setPageSize(pageSize);
//        return caseService.queryPageList(caseBo, pageQuery).getData().getRows();

        return Arrays.asList("1", "2", "3", "4", "5");
    }
}
