package demo.ai.mcp.server.outputschema.dataobject;

/**
 * @Auther: ityangs@163.com
 * @Date: 2025/5/20 15:59
 * @Description:
 */
public class Result<T> {
    private String code;
    private String message;
    private T data;

    // 省略构造器、getter、setter

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        return result;
    }
}

