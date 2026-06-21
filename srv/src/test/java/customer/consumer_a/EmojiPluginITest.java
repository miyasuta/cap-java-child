
package customer.consumer_a;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EmojiPluginITest {

    private static final String BOOKS_URL = "/odata/v4/CatalogService/Books";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readShouldDecorateTitleWithEmoji() throws Exception {
        // Arrange: create a book
        mockMvc.perform(post(BOOKS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Wuthering Heights\",\"author\":\"Emily Bronte\"}"))
                .andExpect(status().isCreated());

        // Act + Assert: reading it back returns the emoji-decorated title
        mockMvc.perform(get(BOOKS_URL + "?$filter=title eq 'Wuthering Heights'"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value[0].title").value(containsString("🎉")));
    }

}