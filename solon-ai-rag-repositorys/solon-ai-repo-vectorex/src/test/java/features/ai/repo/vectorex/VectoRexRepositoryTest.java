package features.ai.repo.vectorex;

import io.github.javpower.vectorexclient.VectorRexClient;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.VectoRexRepository;

public class VectoRexRepositoryTest {
    private String url;
    private String username;
    private String password;

    @Test
    public void case1() throws Exception {
        VectorRexClient client = new VectorRexClient(url, username, password);

        VectoRexRepository repository = VectoRexRepository
                .builder(null, client)
                .build();
    }
}
