package ru.romanov;

import com.google.gson.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CrptApi {
    private final TimeUnit timeUnit;
    private final int requestLimit;
    private int requestCount;
    private long lastResetTime;
    private Gson gson;
    private final String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.requestCount = 0;
        this.lastResetTime = System.currentTimeMillis();
        gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new LocalDateTypeAdapter())
                .create();
    }

    public void createDocument(Document document, String signature) {
        DocumentRequest documentRequest = new DocumentRequest(document, signature);
        String requestJson = gson.toJson(documentRequest);
        HttpEntity httpEntity = new StringEntity(requestJson, ContentType.APPLICATION_JSON);

        synchronized (this) {
            // Проверка, нужно ли обнулить счетчик запросов
            long currentTime = System.currentTimeMillis();
            long timePassed = currentTime - lastResetTime;
            if (timePassed >= timeUnit.toMillis(1)) {
                requestCount = 0;
                lastResetTime = currentTime;
            }

            // Проверяем, не превышен ли лимит запросов
            if (requestCount >= requestLimit) {
                try {
                    // Если лимит превышен, ждем до окончания текущего интервала
                    long sleepTime = timeUnit.toMillis(1) - timePassed;
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // После ожидания обнуляем счетчик и время
                requestCount = 0;
                lastResetTime = System.currentTimeMillis();
            }
            // Выполняем запрос к API
            apiRequest(httpEntity);
            requestCount++;
        }
    }

    private void apiRequest(HttpEntity httpEntity) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(URL);
            httpPost.setEntity(httpEntity);

            httpPost.setHeader("content-type", "application/json");

            // Получаем и обрабатываем ответ
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    System.out.println("Ошибка соединения");
                    return;
                }
                HttpEntity responseEntity = response.getEntity();

                if (responseEntity != null) {
                    // обработка JSON-ответа с результатами создания документа
                    String responseJson = EntityUtils.toString(responseEntity);
                    DocumentResponse documentResponse = gson.fromJson(responseJson, DocumentResponse.class);
                    String documentId = documentResponse.getValue();
                    if (documentId != null) {
                        // Обработка успешного ответа
                        System.out.println("Документ создан");
                    } else {
                        // Обработка неуспешного ответа (ошибка)
                        System.out.println("Ошибка создания документа");
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Getter
    @AllArgsConstructor
    private static class Document {
        private Description description;
        private String doc_id;
        private String doc_status;
        private DocType doc_type;
        private boolean importRequest;
        private String owner_inn;
        private LocalDate production_date;
        private String production_type;
        private List<Product> products;
        private LocalDate reg_date;
        private String reg_number;
    }

    @Getter
    @AllArgsConstructor
    private static class Description {
        private String participantInn;
    }

    @Getter
    @AllArgsConstructor
    private static class Product {
        private String certificate_document;
        private LocalDate certificate_document_date;
        private String certificate_document_number;
        private String owner_inn;
        private String producer_inn;
        private LocalDate production_date;
        private String tnved_code;
        private String uit_code;
        private String uitu_code;
    }

    @Getter
    @AllArgsConstructor
    private static class DocumentRequest {
        private Document document;
        private String signature;
    }

    @Getter
    @AllArgsConstructor
    private static class DocumentResponse {
        private String value;
        private String errorCode;
        private String errorMessage;
        private String errorDescription;
    }

    enum DocType {
        LP_INTRODUCE_GOODS
    }

    public class LocalDateTypeAdapter implements JsonSerializer<LocalDate>, JsonDeserializer<LocalDate> {
        private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        @Override
        public JsonElement serialize(final LocalDate date, final Type typeOfSrc,
                                     final JsonSerializationContext context) {
            return new JsonPrimitive(date.format(formatter));
        }
        @Override
        public LocalDate deserialize(final JsonElement json, final Type typeOfT,
                                     final JsonDeserializationContext context) throws JsonParseException {
            return LocalDate.parse(json.getAsString(), formatter);
        }
    }
}