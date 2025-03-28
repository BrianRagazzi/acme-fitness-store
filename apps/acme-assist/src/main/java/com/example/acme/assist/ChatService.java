package com.example.acme.assist;

import com.example.acme.assist.model.AcmeChatMessage;
import com.example.acme.assist.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.apache.logging.log4j.util.Strings;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ChatService {

    @Autowired
    private VectorStore store;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private List<String> mcpServiceURLs;

    @Autowired
    private SSLContext sslContext;

    @Value("classpath:/prompts/chatWithoutProductId.st")
    private Resource chatWithoutProductIdResource;

    @Value("classpath:/prompts/chatWithProductId.st")
    private Resource chatWithProductIdResource;

    /**
     * Chat with the OpenAI API. Use the product details as the context.
     *
     * @param chatRequestMessages the chat messages
     * @return the chat response
     */
    public List<String> chat(List<AcmeChatMessage> chatRequestMessages, String productId) {

        validateMessage(chatRequestMessages);

        // step 1. Retrieve the product if available
        Product product = productRepository.getProductById(productId);
        // If no specific product is found, search the vector store to find something that matches the request.
        if (product == null) {
            return chatWithoutProductId(chatRequestMessages);
        } else {
            return chatWithProductId(product, chatRequestMessages);
        }
    }

    private List<String> chatWithProductId(Product product, List<AcmeChatMessage> chatRequestMessages) {
        // We have a specific Product
        String question = chatRequestMessages.getLast().getContent();

        // step 1. Query for documents that are related to the question from the vector store
        SearchRequest request = new SearchRequest.Builder().
                query(question).
                topK(5).
                similarityThreshold(0.4).
                build();
        List<Document> candidateDocuments = this.store.similaritySearch(request);

        // step 2. Create a SystemMessage that contains the product information in addition to related documents.
        List<Message> messages = new ArrayList<>();
        Message productDetailMessage =  getProductDetailMessage(product, candidateDocuments);
        messages.add(productDetailMessage);

        // step 3. Send the system message and the user's chat request messages to OpenAI
        return addUserMessagesAndSendToAI(chatRequestMessages, messages);
    }

    /**
     * Chat with the OpenAI API. Search the vector store for the top 5 related documents
     * to the questions and use them as the system context.
     *
     * @param acmeChatRequestMessages the chat messages, including previous messages sent by the client
     * @return the chat response
     */
    protected List<String> chatWithoutProductId(List<AcmeChatMessage> acmeChatRequestMessages) {

        String question = acmeChatRequestMessages.getLast().getContent();

        // step 1. Query for documents that are related to the question from the vector store
        SearchRequest request = SearchRequest.builder().
                query(question).
                topK(5).
                similarityThreshold(0.4).
                build();
        List<Document> relatedDocuments = store.similaritySearch(request);


        // step 2. Create the system message with the related documents;
        List<Message> messages = new ArrayList<>();
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(this.chatWithoutProductIdResource);
        String relatedDocsAsString = relatedDocuments.stream()
                .map(entry -> String.format("Product Name: %s\nText: %s\n", entry.getMetadata().get("name"), entry.getText()))
                .collect(Collectors.joining("\n"));
        Message systemMessage = systemPromptTemplate.createMessage(Map.of("context", relatedDocsAsString));
        messages.add(systemMessage);

        // step 3. Send the system message and the user's chat request messages to OpenAI
        return addUserMessagesAndSendToAI(acmeChatRequestMessages, messages);
    }

    private List<String> addUserMessagesAndSendToAI(List<AcmeChatMessage> acmeChatRequestMessages, List<Message> messages) {
        // Convert to acme messages types to Spring AI message types
        for (AcmeChatMessage acmeChatRequestMessage : acmeChatRequestMessages) {
            messages.add(new UserMessage(acmeChatRequestMessage.getContent()));
        }

        // Call to OpenAI chat API
        Prompt prompt = new Prompt(messages);

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(30));
        try ( // Open all McpSyncClients in try-with-resources
              Stream<McpSyncClient> mcpSyncClients = mcpServiceURLs.stream()
                      .map(url -> {
                          HttpClientSseClientTransport transport = new HttpClientSseClientTransport(
                                  clientBuilder, url, new ObjectMapper());
                          return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
                      })
                      .peek(McpSyncClient::initialize)) {
            ToolCallbackProvider[] toolCallbackProviders = mcpSyncClients
                    .map(SyncMcpToolCallbackProvider::new)
                    .toArray(ToolCallbackProvider[]::new);

            ChatResponse aiResponse = this.chatClient.
                    prompt(prompt).
                    tools(toolCallbackProviders).
                    call().
                    chatResponse();

            // Process the result and return to client
            List<String> response = processResult(aiResponse);
            return response;
        }
    }

    public Message getProductDetailMessage(Product product, List<Document> documents) {
        String additionalContext = documents.stream()
                .map(entry -> String.format("Product Name: %s\nText: %s\n", entry.getMetadata().get("name"), entry.getText()))
                .collect(Collectors.joining("\n"));
        Map<String,Object> map = Map.of(
                "name", product.getName(),
                "tags", String.join(",", product.getTags()),
                "shortDescription", product.getShortDescription(),
                "fullDescription", product.getDescription(),
                "additionalContext", additionalContext);
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(this.chatWithProductIdResource);
        return systemPromptTemplate.create(map).getInstructions().getFirst();
    }

    private List<String> processResult(ChatResponse aiResponse) {
        List<String> response = aiResponse.getResults().stream()
                .map(result -> result.getOutput().getText())
                .filter(text -> !StringUtils.isEmpty(text))
                .map(this::filterMessage)
                .collect(Collectors.toList());
        return response;
    }

    private static void validateMessage(List<AcmeChatMessage> acmeChatMessages) {
        if (acmeChatMessages == null || acmeChatMessages.isEmpty()) {
            throw new IllegalArgumentException("message shouldn't be empty.");
        }

        if (acmeChatMessages.getFirst().getRole() != MessageType.USER) {
            throw new IllegalArgumentException("The first message should be in user role.");
        }

        var lastUserMessage = acmeChatMessages.get(acmeChatMessages.size() - 1);
        if (lastUserMessage.getRole() != MessageType.USER) {
            throw new IllegalArgumentException("The last message should be in user role.");
        }
    }

    private String filterMessage(String content) {
        if (Strings.isEmpty(content)) {
            return "";
        }
        List<Product> products = productRepository.getProductList();
        for (Product product : products) {
            content = content.replace(product.getName(), "{{" + product.getName() + "|" + product.getId() + "}}");
        }
        return content;
    }
}
