package features.ai.talent.openapi;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.gateway.OpenApiGatewayTalent;
import org.noear.solon.ai.talents.gateway.openapi.ApiSource;
import org.noear.solon.ai.talents.gateway.openapi.ApiSourceClient;
import org.noear.solon.ai.talents.gateway.openapi.ApiTool;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 *
 * @author noear 2026/6/3 created
 *
 */
public class Petstore2ApiTest {
    private String docUrl = "https://petstore.swagger.io/v2/swagger.json";
    private String baseUrl = "https://petstore.swagger.io/v2/";

    @Test
    public void case1_loadApi() {
        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(docUrl, baseUrl);

        ApiSourceClient sourceClient = openApiTalent.getApiSource(docUrl);
        Assertions.assertNotNull(sourceClient, "getApiSource 应返回已注册的客户端");

        // 验证基础配置
        Assertions.assertEquals(docUrl, sourceClient.getDocUrl());
        Assertions.assertEquals(baseUrl, sourceClient.getSource().getApiBaseUrl());

        // 验证工具已加载
        Collection<ApiTool> tools = sourceClient.getTools();
        Assertions.assertFalse(tools.isEmpty(), "Petstore3 应解析出工具");

        System.out.println("已加载全量工具数: " + tools.size());
        tools.forEach(t -> System.out.println("  - " + t.getName() + " (" + t.getMethod() + " " + t.getPath() + ")"));
    }

    @Test
    public void case2_hasApiSource() {
        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(docUrl, baseUrl);

        Assertions.assertTrue(openApiTalent.hasApiSource(docUrl));
        Assertions.assertFalse(openApiTalent.hasApiSource("https://not-exists.com/api.json"));
    }

    @Test
    public void case3_allowedTools_filter() {
        // 仅允许 getPetById 和 findPetsByStatus
        ApiSource source = new ApiSource();
        source.setDocUrl(docUrl);
        source.setApiBaseUrl(baseUrl);
        source.setAllowedTools(Arrays.asList("getPetById", "findPetsByStatus"));

        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(source);

        ApiSourceClient sourceClient = openApiTalent.getApiSource(docUrl);
        Assertions.assertNotNull(sourceClient);

        // 全量工具应不受影响
        Collection<ApiTool> allTools = sourceClient.getTools();
        Assertions.assertFalse(allTools.isEmpty());

        // 过滤后的激活工具应只有 2 个
        Collection<ApiTool> activatedTools = sourceClient.getToolsActivated();
        Assertions.assertEquals(2, activatedTools.size(), "白名单过滤后应只剩 2 个工具");
        activatedTools.forEach(t -> {
            Assertions.assertTrue(
                    "getPetById".equals(t.getName()) || "findPetsByStatus".equals(t.getName()),
                    "激活工具应为白名单中的工具，实际: " + t.getName()
            );
        });

        System.out.println("白名单过滤 - 全量: " + allTools.size() + ", 激活: " + activatedTools.size());
    }

    @Test
    public void case4_disallowedTools_filter() {
        // 禁用 deletePet
        ApiSource source = new ApiSource();
        source.setDocUrl(docUrl);
        source.setApiBaseUrl(baseUrl);
        source.setDisallowedTools(Arrays.asList("deletePet"));

        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(source);

        ApiSourceClient sourceClient = openApiTalent.getApiSource(docUrl);
        Collection<ApiTool> activatedTools = sourceClient.getToolsActivated();

        // deletePet 不应在激活列表中
        boolean hasDeletePet = activatedTools.stream()
                .anyMatch(t -> "deletePet".equals(t.getName()));
        Assertions.assertFalse(hasDeletePet, "deletePet 应被黑名单过滤");

        System.out.println("黑名单过滤 - 激活工具数: " + activatedTools.size() + " (不含 deletePet)");
    }

    @Test
    public void case5_removeApi() {
        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(docUrl, baseUrl);

        Assertions.assertTrue(openApiTalent.hasApiSource(docUrl));

        openApiTalent.removeApi(docUrl);
        Assertions.assertFalse(openApiTalent.hasApiSource(docUrl), "移除后应不再包含该源");
    }

    @Test
    public void case6_toolFields_populated() {
        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(docUrl, baseUrl);

        ApiSourceClient sourceClient = openApiTalent.getApiSource(docUrl);
        Collection<ApiTool> tools = sourceClient.getTools();

        // 找到 getPetById 并验证字段
        ApiTool getPetById = tools.stream()
                .filter(t -> "getPetById".equals(t.getName()))
                .findFirst()
                .orElse(null);

        Assertions.assertNotNull(getPetById, "应包含 getPetById 工具");
        Assertions.assertEquals("GET", getPetById.getMethod());
        Assertions.assertTrue(getPetById.getPath().contains("{petId}"), "路径应包含 {petId} 参数");
        Assertions.assertNotNull(getPetById.getTags());
        Assertions.assertFalse(getPetById.getTags().isEmpty(), "Petstore 接口应有 tag 分组");
        Assertions.assertNotNull(getPetById.getDescription(), "应有描述信息");

        System.out.println("getPetById 验证通过: " + getPetById.getMethod() + " " + getPetById.getPath()
                + " | tags=" + getPetById.getTags() + " | desc=" + getPetById.getDescription());
    }

    @Test
    public void case7_searchApis() {
        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(docUrl, baseUrl);

        // 搜索包含 pet 关键字的接口
        Object result = openApiTalent.searchApis("pet");
        Assertions.assertNotNull(result);

        System.out.println("搜索 'pet' 结果: " + result);
    }

    @Test
    public void case8_getApiDetail() {
        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(docUrl, baseUrl);

        String detail = openApiTalent.getApiDetail("getPetById");
        Assertions.assertNotNull(detail);
        Assertions.assertTrue(detail.contains("getPetById"), "详情应包含接口名");

        System.out.println("getPetById 详情:\n" + detail);
    }

    /**
     * 验证运行时权限变更：在 client 副本上 addAllowedTool，不影响 ApiSource 原始配置
     */
    @Test
    public void case9_runtimePermissionChange() {
        // 初始无过滤
        ApiSource source = new ApiSource();
        source.setDocUrl(docUrl);
        source.setApiBaseUrl(baseUrl);

        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(source);

        ApiSourceClient sourceClient = openApiTalent.getApiSource(docUrl);
        int originalActivated = sourceClient.getToolsActivated().size();

        // 在 client 副本上动态添加白名单限制（不应修改 source）
        sourceClient.getAllowedTools().add("getPetById");


        // client 的白名单应已更新
        Collection<String> clientAllowed = sourceClient.getAllowedTools();
        Assertions.assertEquals(1, clientAllowed.size(), "client 应有 1 个白名单工具");
        Assertions.assertEquals("getPetById", clientAllowed.stream().findFirst().get());

        // source 的白名单应保持为空（未被污染）
        List<String> sourceAllowed = source.getAllowedTools();
        Assertions.assertTrue(sourceAllowed.isEmpty(), "source 的白名单应保持为空");

        // 刷新后激活工具应只剩 1 个
        openApiTalent.refreshApi(docUrl);
        Collection<ApiTool> refreshedTools = sourceClient.getToolsActivated();
        Assertions.assertEquals(1, refreshedTools.size(), "刷新后白名单应只保留 1 个工具");
        Assertions.assertEquals("getPetById", refreshedTools.iterator().next().getName());

        System.out.println("权限刷新 - 之前: " + originalActivated + ", 之后: " + refreshedTools.size());
    }

    /**
     * 验证 client 的 setDisallowedTools 不影响 source 原始数据
     */
    @Test
    public void case10_clientDisallowedNotAffectSource() {
        ApiSource source = new ApiSource();
        source.setDocUrl(docUrl);
        source.setApiBaseUrl(baseUrl);

        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(source);

        ApiSourceClient sourceClient = openApiTalent.getApiSource(docUrl);

        // 在 client 副本上禁用 deletePet
        sourceClient.getDisallowedTools().add("deletePet");

        // client 应有黑名单
        Assertions.assertEquals(1, sourceClient.getDisallowedTools().size());
        Assertions.assertEquals("deletePet", sourceClient.getDisallowedTools().stream().findFirst().get());

        // source 应无黑名单
        Assertions.assertTrue(source.getDisallowedTools().isEmpty(), "source 的黑名单应保持为空");

        // 激活工具中不应有 deletePet
        boolean hasDeletePet = sourceClient.getToolsActivated().stream()
                .anyMatch(t -> "deletePet".equals(t.getName()));
        Assertions.assertFalse(hasDeletePet, "deletePet 应被 client 黑名单过滤");

        System.out.println("client 黑名单独立验证通过");
    }

    /**
     * 验证通过 client 批量设置白名单
     */
    @Test
    public void case11_clientSetAllowedTools() {
        ApiSource source = new ApiSource();
        source.setDocUrl(docUrl);
        source.setApiBaseUrl(baseUrl);
        source.setAllowedTools(Arrays.asList("getPetById")); // source 初始白名单

        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(source);

        ApiSourceClient sourceClient = openApiTalent.getApiSource(docUrl);

        // 初始时 client 从 source 复制了白名单
        Assertions.assertEquals(Arrays.asList("getPetById"), sourceClient.getAllowedTools());

        // 通过 client 覆盖白名单
        sourceClient.setAllowedTools(Arrays.asList("findPetsByStatus", "findPetsByTags"));

        // client 应已更新
        Assertions.assertEquals(2, sourceClient.getAllowedTools().size());
        Assertions.assertTrue(sourceClient.getAllowedTools().contains("findPetsByStatus"));

        // source 应保持不变
        Assertions.assertEquals(Arrays.asList("getPetById"), source.getAllowedTools());

        System.out.println("client 批量设置白名单验证通过");
    }

    @Test
    public void case12_disabledSource() {
        // 禁用的源不应被加载
        ApiSource source = new ApiSource();
        source.setDocUrl(docUrl);
        source.setApiBaseUrl(baseUrl);
        source.setEnabled(false);

        OpenApiGatewayTalent openApiTalent = new OpenApiGatewayTalent();
        openApiTalent.addApi(source);

        Assertions.assertFalse(openApiTalent.hasApiSource(docUrl), "禁用的源不应被注册");
    }
}
