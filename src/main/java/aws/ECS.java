package aws;

import io.github.cdimascio.dotenv.Dotenv;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.Task;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class ECS {
    private static final String ECS_ACCESS_KEY_ID;
    private static final String ECS_SECRET_ACCESS_KEY;

    static {
        Dotenv dotenv = Dotenv.load();
        ECS_ACCESS_KEY_ID = dotenv.get("ECS_ACCESS_KEY_ID");
        ECS_SECRET_ACCESS_KEY = dotenv.get("ECS_SECRET_ACCESS_KEY");
    }

    public static String getECSServiceId() {
        String metadataUri = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
        String serviceDeploymentId = null;

        if (metadataUri == null || metadataUri.isEmpty()) {
            System.out.println("메타데이터 엔드포인트 환경 변수가 설정되어 있지 않습니다.");
            return null;
        }

        try {
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

                // 메타데이터를 활용해서 service 정보 읽어내기
                Region region = Region.US_EAST_1;
                AwsBasicCredentials awsCreds = AwsBasicCredentials.create(ECS_ACCESS_KEY_ID, ECS_SECRET_ACCESS_KEY);

                EcsClient ecsClient = EcsClient.builder()
                        .region(region)
                        .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                        .build();

                JSONObject jsonObject = new JSONObject(response.toString());
                JSONObject labels = jsonObject.getJSONObject("Labels");
                String clusterArn = labels.getString("com.amazonaws.ecs.cluster");
                String taskArn = labels.getString("com.amazonaws.ecs.task-arn");

                DescribeTasksRequest tasksRequest = DescribeTasksRequest.builder()
                        .cluster(clusterArn)
                        .tasks(taskArn)
                        .build();

                DescribeTasksResponse tasksResponse = ecsClient.describeTasks(tasksRequest);
                Task task = tasksResponse.tasks().get(0);
                String startedBy = task.startedBy();  // ecs-svc/xxxxxxx
                serviceDeploymentId = "/" + startedBy.split("/")[1];

                System.out.println(serviceDeploymentId);
                ecsClient.close();

            } else {
                System.out.println("메타데이터 요청 실패. 응답 코드: " + responseCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return serviceDeploymentId;
    }

    public static String getTaskArn() {
        String metadataUri = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
        String taskArn = null;

        if (metadataUri == null || metadataUri.isEmpty()) {
            System.out.println("메타데이터 엔드포인트 환경 변수가 설정되어 있지 않습니다.");
            return null;
        }

        try {
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

                // 메타데이터를 활용해서 service 정보 읽어내기
                Region region = Region.US_EAST_1;
                AwsBasicCredentials awsCreds = AwsBasicCredentials.create(ECS_ACCESS_KEY_ID, ECS_SECRET_ACCESS_KEY);

                EcsClient ecsClient = EcsClient.builder()
                        .region(region)
                        .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                        .build();

                JSONObject jsonObject = new JSONObject(response.toString());
                JSONObject labels = jsonObject.getJSONObject("Labels");
                taskArn = labels.getString("com.amazonaws.ecs.task-arn");
                String[] parts = taskArn.split("/");
                taskArn = "/" + parts[parts.length - 1];

                System.out.println(taskArn);
                ecsClient.close();

            } else {
                System.out.println("메타데이터 요청 실패. 응답 코드: " + responseCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return taskArn;
    }
}
