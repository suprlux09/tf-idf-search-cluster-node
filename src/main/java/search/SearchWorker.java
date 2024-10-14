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

import model.DocumentData;
import model.Result;
import model.SerializationUtils;
import model.Task;
import networking.OnRequestCallback;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class SearchWorker implements OnRequestCallback {
    private static final String ENDPOINT = "/task";

    public byte[] handleRequest(byte[] requestPayload) {
        String threadName = Thread.currentThread().getName();
        long threadId = Thread.currentThread().getId();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime startTime = LocalDateTime.now();
        System.out.println("[" + threadName + " (ID: " + threadId + ")] worker 작업 시작 시각: " + startTime.format(formatter));

        Task task = (Task) SerializationUtils.deserialize(requestPayload);
        Result result = createResult(task);

        LocalDateTime endTime = LocalDateTime.now();
        System.out.println("[" + threadName + " (ID: " + threadId + ")] worker 작업 완료 시각: " + endTime.format(formatter));

        Duration duration = Duration.between(startTime, endTime);
        long seconds = duration.getSeconds();
        long millis = duration.toMillisPart();
        System.out.println("[" + threadName + " (ID: " + threadId + ")] worker 작업 소요 시간: " + seconds + "s " + millis + "ms");

        return SerializationUtils.serialize(result);
    }

    @Override
    public String getEndpoint() {
        return ENDPOINT;
    }

    private Result createResult(Task task) {
        List<String> documents = task.getDocuments();
        System.out.println(String.format("Received %d documents to process", documents.size()));
        Result result = new Result();

        String threadName = Thread.currentThread().getName();
        long threadId = Thread.currentThread().getId();

        for (String document : documents) {
            LocalDateTime startTime = LocalDateTime.now();

            List<String> words = parseWordsFromDocument(document);
            DocumentData documentData = TFIDF.createDocumentData(words, task.getSearchTerms());
            result.addDocumentData(document, documentData);

            LocalDateTime endTime = LocalDateTime.now();
            Duration duration = Duration.between(startTime, endTime);
            long seconds = duration.getSeconds();
            long millis = duration.toMillisPart();
            System.out.println("[" + threadName + " (ID: " + threadId + ")] " + document + " " + seconds + "s " + millis + "ms");
        }
        return result;
    }

    private List<String> parseWordsFromDocument(String document) {
        BufferedReader bufferedReader = null;
        try {
            URL url = new URL(document);
            InputStream inputStream = url.openStream();
            bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
        List<String> lines = bufferedReader.lines().collect(Collectors.toList());
        List<String> words = TFIDF.getWordsFromDocument(lines);
        return words;
    }
}
