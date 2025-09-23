package demo.ai.mcp.server;


public class _Constants {
    //换成自己的模型配置（参考：https://solon.noear.org/article/918）
    public static final String chat_apiUrl = "http://127.0.0.1:11434/api/chat";
    public static final String chat_provider = "ollama";
    public static final String chat_model = "qwen2.5:1.5b"; //"llama3.2";//deepseek-r1:1.5b;



    //换成自己的模型配置（参考：https://solon.noear.org/article/934）
    public static final String embedding_apiUrl = "http://127.0.0.1:11434/api/embed";
    public static final String embedding_provider = "ollama";
    public static final String embedding_model = "bge-m3";//
}
