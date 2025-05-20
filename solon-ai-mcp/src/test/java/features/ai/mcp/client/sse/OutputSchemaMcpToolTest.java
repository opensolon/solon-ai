package features.ai.mcp.client.sse;

import demo.ai.mcp.server.McpServerApp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.test.SolonTest;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * @author noear 2025/5/20 created
 */
@Slf4j
@SolonTest(McpServerApp.class)
public class OutputSchemaMcpToolTest {
    McpClientProvider mcpClient = McpClientProvider.builder()
            .apiUrl("http://localhost:8081/mcp/outputSchema/sse")
            .build();

    public Map<String, FunctionTool> getToolMap() throws Exception {
        Map<String, FunctionTool> toolMap = new HashMap<>();
        Collection<FunctionTool> tools = mcpClient.getTools();
        for (FunctionTool tool : tools) {
            toolMap.put(tool.name(), tool);
        }
        return toolMap;
    }

    @Test
    public void getWeather0() throws Exception {
        //public String getWeather0(@Param(description = "城市") String city) {
        FunctionTool tool = getToolMap().get("getWeather0");
        log.warn(tool.toString());
    }

    @Test
    public void getWeather() throws Exception {
        //public String getWeather(@Param(description = "城市") String city) {
        FunctionTool tool = getToolMap().get("getWeather");
        log.warn(tool.toString());
    }

    @Test
    public void getUserInfo() throws Exception {
        //public UserInfo getUserInfo(@Param(description = "用户ID") Long userId) {
        FunctionTool tool = getToolMap().get("getUserInfo");
        log.warn(tool.toString());
    }

    @Test
    public void listCities() throws Exception {
        //public Result<List<CityInfo>> listCities() {
        FunctionTool tool = getToolMap().get("listCities");
        log.warn(tool.toString());
    }

    @Test
    public void getConfigs() throws Exception {
        //public Map<String, Object> getConfigs() {
        FunctionTool tool = getToolMap().get("getConfigs");
        log.warn(tool.toString());
    }

    @Test
    public void getCurrentUser() throws Exception {
        //public Result<UserInfo> getCurrentUser() {
        FunctionTool tool = getToolMap().get("getCurrentUser");
        log.warn(tool.toString());
    }

    @Test
    public void getSetting() throws Exception {
        //public Optional<String> getSetting(@Param(description = "键") String key) {
        FunctionTool tool = getToolMap().get("getSetting");
        log.warn(tool.toString());
    }

    @Test
    public void getTags() throws Exception {
        //public Result<String[]> getTags() {
        FunctionTool tool = getToolMap().get("getTags");
        log.warn(tool.toString());
    }
}
