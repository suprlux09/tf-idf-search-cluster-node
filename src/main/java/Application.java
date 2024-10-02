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

import cluster.management.LeaderElection;
import cluster.management.ServiceRegistry;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Search Cluster Coordinator - Distributed Search Part 2
 */
public class Application implements Watcher {
    private static final String ZOOKEEPER_ADDRESS;
    private static final int SESSION_TIMEOUT = 3000;
    private ZooKeeper zooKeeper;

    static {
        Dotenv dotenv = Dotenv.load();
        ZOOKEEPER_ADDRESS = dotenv.get("ZOOKEEPER_ADDRESS");
    }

    public static void main(String[] args) throws IOException, InterruptedException, KeeperException {
        int currentServerPort = 8080;
        if (args.length == 1) {
            currentServerPort = Integer.parseInt(args[0]);
        }
        Application application = new Application();
        ZooKeeper zooKeeper = application.connectToZookeeper();

        // TODO: AWS ECS service 정보를 가져오도록 하기
        String clusterZnode = "";
        getECSServiceMetadata();

        ServiceRegistry workersServiceRegistry = new ServiceRegistry(zooKeeper, clusterZnode, ServiceRegistry.WORKERS_REGISTRY_ZNODE);
        ServiceRegistry coordinatorsServiceRegistry = new ServiceRegistry(zooKeeper, clusterZnode, ServiceRegistry.COORDINATORS_REGISTRY_ZNODE);

        OnElectionAction onElectionAction = new OnElectionAction(workersServiceRegistry, coordinatorsServiceRegistry, currentServerPort);

        LeaderElection leaderElection = new LeaderElection(zooKeeper, clusterZnode, onElectionAction);
        leaderElection.volunteerForLeadership();
        leaderElection.reelectLeader();

        application.run();
        application.close();
        System.out.println("Disconnected from Zookeeper, exiting application");
    }

    private static String getECSServiceMetadata() {
        String metadataUri = System.getenv("ECS_CONTAINER_METADATA_URI_V4");

        if (metadataUri == null || metadataUri.isEmpty()) {
            System.out.println("메타데이터 엔드포인트 환경 변수가 설정되어 있지 않습니다.");
            return null;
        }

        try {
            System.out.println(metadataUri);

            // 메타데이터 엔드포인트에 HTTP GET 요청 보내기
            URL url = new URL(metadataUri);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            // 응답 코드 확인
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 응답 읽기
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                // 메타데이터 출력 (JSON 형식)
                System.out.println("메타데이터 응답: " + response.toString());
                return response.toString();
            } else {
                System.out.println("메타데이터 요청 실패. 응답 코드: " + responseCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public ZooKeeper connectToZookeeper() throws IOException {
        this.zooKeeper = new ZooKeeper(ZOOKEEPER_ADDRESS, SESSION_TIMEOUT, this);
        return zooKeeper;
    }

    public void run() throws InterruptedException {
        synchronized (zooKeeper) {
            zooKeeper.wait();
        }
    }

    public void close() throws InterruptedException {
        zooKeeper.close();
    }

    @Override
    public void process(WatchedEvent event) {
        switch (event.getType()) {
            case None:
                if (event.getState() == Event.KeeperState.SyncConnected) {
                    System.out.println("Successfully connected to Zookeeper");
                } else {
                    synchronized (zooKeeper) {
                        System.out.println("Disconnected from Zookeeper event");
                        zooKeeper.notifyAll();
                    }
                }
        }
    }
}
