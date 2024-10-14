/*
 *  MIT License
 *
 *  Copyright (c) 2019 Michael Pogrebinsky - Distributed Systems & Cloud Computing with Java
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package search;

import cluster.management.ServiceRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.cdimascio.dotenv.Dotenv;
import model.DocumentData;
import model.Result;
import model.SerializationUtils;
import model.Task;
import model.proto.SearchModel;
import networking.OnRequestCallback;
import networking.WebClient;
import org.apache.zookeeper.KeeperException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.net.URLEncoder.encode;

/**
 * Search Cluster Coordinator - Distributed Search Part 2
 */
public class SearchCoordinator implements OnRequestCallback {
    private static final String ENDPOINT = "/search";
    private static final String BOOKS_DIRECTORY;
    private static final String S3_ACCESS_KEY_ID;
    private static final String S3_SECRET_ACCESS_KEY;
    private final ServiceRegistry workersServiceRegistry;
    private final WebClient client;
    private final List<String> documents;

    static {
        Dotenv dotenv = Dotenv.load();
        BOOKS_DIRECTORY = dotenv.get("BOOKS_DIRECTORY");
        S3_ACCESS_KEY_ID = dotenv.get("S3_ACCESS_KEY_ID");
        S3_SECRET_ACCESS_KEY = dotenv.get("S3_SECRET_ACCESS_KEY");
    }

    public SearchCoordinator(ServiceRegistry workersServiceRegistry, WebClient client) {
        this.workersServiceRegistry = workersServiceRegistry;
        this.client = client;
        this.documents = readDocumentsList();
    }

    public byte[] handleRequest(byte[] requestPayload) {
        String threadName = Thread.currentThread().getName();
        long threadId = Thread.currentThread().getId();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime startTime = LocalDateTime.now();
        System.out.println("[" + threadName + " (ID: " + threadId + ")] master 작업 시작 시각: " + startTime.format(formatter));

        try {
            SearchModel.Request request = SearchModel.Request.parseFrom(requestPayload);
            SearchModel.Response response = createResponse(request);

            LocalDateTime endTime = LocalDateTime.now();
            System.out.println("[" + threadName + " (ID: " + threadId + ")] master 작업 완료 시각: " + endTime.format(formatter));

            Duration duration = Duration.between(startTime, endTime);
            long seconds = duration.getSeconds();
            long millis = duration.toMillisPart();
            System.out.println("[" + threadName + " (ID: " + threadId + ")] master 작업 소요 시간: " + seconds + "s " + millis + "ms");

            return response.toByteArray();
        } catch (InvalidProtocolBufferException | KeeperException | InterruptedException e) {
            e.printStackTrace();
            return SearchModel.Response.getDefaultInstance().toByteArray();
        }
    }

    @Override
    public String getEndpoint() {
        return ENDPOINT;
    }

    private SearchModel.Response createResponse(SearchModel.Request searchRequest) throws KeeperException, InterruptedException {
        SearchModel.Response.Builder searchResponse = SearchModel.Response.newBuilder();

        System.out.println("Received search query: " + searchRequest.getSearchQuery());

        List<String> searchTerms = TFIDF.getWordsFromLine(searchRequest.getSearchQuery());

        List<String> workers = workersServiceRegistry.getAllServiceAddresses();

        if (workers.isEmpty()) {
            System.out.println("No search workers currently available");
            return searchResponse.build();
        }

        List<Task> tasks = createTasks(workers.size(), searchTerms);
        List<Result> results = sendTasksToWorkers(workers, tasks);

        List<SearchModel.Response.DocumentStats> sortedDocuments = aggregateResults(results, searchTerms);
        searchResponse.addAllRelevantDocuments(sortedDocuments);

        return searchResponse.build();
    }

    private List<SearchModel.Response.DocumentStats> aggregateResults(List<Result> results, List<String> terms) {
        Map<String, DocumentData> allDocumentsResults = new HashMap<>();

        for (Result result : results) {
            allDocumentsResults.putAll(result.getDocumentToDocumentData());
        }

        System.out.println("Calculating score for all the documents");
        Map<Double, List<String>> scoreToDocuments = TFIDF.getDocumentsScores(terms, allDocumentsResults);

        return sortDocumentsByScore(scoreToDocuments);
    }

    private List<SearchModel.Response.DocumentStats> sortDocumentsByScore(Map<Double, List<String>> scoreToDocuments) {
        List<SearchModel.Response.DocumentStats> sortedDocumentsStatsList = new ArrayList<>();

        for (Map.Entry<Double, List<String>> docScorePair : scoreToDocuments.entrySet()) {
            double score = docScorePair.getKey();

            for (String document : docScorePair.getValue()) {
                File documentPath = new File(document);

                SearchModel.Response.DocumentStats documentStats = SearchModel.Response.DocumentStats.newBuilder()
                        .setScore(score)
                        .setDocumentName(documentPath.getName())
                        .setDocumentSize(documentPath.length())
                        .build();

                sortedDocumentsStatsList.add(documentStats);
            }
        }

        return sortedDocumentsStatsList;
    }

    private List<Result> sendTasksToWorkers(List<String> workers, List<Task> tasks) {
        CompletableFuture<Result>[] futures = new CompletableFuture[workers.size()];
        for (int i = 0; i < workers.size(); i++) {
            String worker = workers.get(i);
            Task task = tasks.get(i);
            byte[] payload = SerializationUtils.serialize(task);

            futures[i] = client.sendTask(worker, payload);
        }

        List<Result> results = new ArrayList<>();
        for (CompletableFuture<Result> future : futures) {
            try {
                Result result = future.get();
                results.add(result);
            } catch (InterruptedException | ExecutionException e) {
            }
        }
        System.out.println(String.format("Received %d/%d results", results.size(), tasks.size()));
        return results;
    }

    public List<Task> createTasks(int numberOfWorkers, List<String> searchTerms) {
        List<List<String>> workersDocuments = splitDocumentList(numberOfWorkers, documents);

        List<Task> tasks = new ArrayList<>();

        for (List<String> documentsForWorker : workersDocuments) {
            Task task = new Task(searchTerms, documentsForWorker);
            tasks.add(task);
        }

        return tasks;
    }

    private static List<List<String>> splitDocumentList(int numberOfWorkers, List<String> documents) {
        int numberOfDocumentsPerWorker = (documents.size() + numberOfWorkers - 1) / numberOfWorkers;

        List<List<String>> workersDocuments = new ArrayList<>();

        for (int i = 0; i < numberOfWorkers; i++) {
            int firstDocumentIndex = i * numberOfDocumentsPerWorker;
            int lastDocumentIndexExclusive = Math.min(firstDocumentIndex + numberOfDocumentsPerWorker, documents.size());

            if (firstDocumentIndex >= lastDocumentIndexExclusive) {
                break;
            }
            List<String> currentWorkerDocuments = new ArrayList<>(documents.subList(firstDocumentIndex, lastDocumentIndexExclusive));

            workersDocuments.add(currentWorkerDocuments);
        }
        return workersDocuments;
    }

    private static List<String> readDocumentsList() {
        // S3 버킷으로부터 파일명 모두 읽어오기
        Region region = Region.US_EAST_1;
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY);
        try (S3Client s3 = S3Client.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build()) {
            String bucketName = "tf-idf-document-search-documents";
            List<String> objectKeys = new ArrayList<>();
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .build();

            ListObjectsV2Iterable response = s3.listObjectsV2Paginator(listReq);

            for (ListObjectsV2Response page : response) {
                for (S3Object obj : page.contents()) {
                    objectKeys.add(BOOKS_DIRECTORY + "/" + encode(obj.key(), StandardCharsets.UTF_8));
                }
            }

            // 결과 출력
            for (String key : objectKeys) {
                System.out.println(key);
            }

            return objectKeys;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
