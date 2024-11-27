package aws;

import io.github.cdimascio.dotenv.Dotenv;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.net.URLEncoder.encode;

public class S3 {
    private static final String S3_ACCESS_KEY_ID;
    private static final String S3_SECRET_ACCESS_KEY;
    private static final String S3_ACCESS_URL;
    private static final String S3_BUCKET_NAME;


    static {
        Dotenv dotenv = Dotenv.load();
        S3_ACCESS_KEY_ID = dotenv.get("S3_ACCESS_KEY_ID");
        S3_SECRET_ACCESS_KEY = dotenv.get("S3_SECRET_ACCESS_KEY");
        S3_ACCESS_URL = dotenv.get("S3_ACCESS_URL");
        S3_BUCKET_NAME = dotenv.get("S3_BUCKET_NAME");
    }

    public static List<String> readDocumentsList() {
        // S3 버킷으로부터 파일명 모두 읽어오기
        Region region = Region.US_EAST_1;
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY);
        try (S3Client s3 = S3Client.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build()) {
            List<String> objectKeys = new ArrayList<>();
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(S3_BUCKET_NAME)
                    .build();

            ListObjectsV2Iterable response = s3.listObjectsV2Paginator(listReq);

            for (ListObjectsV2Response page : response) {
                for (S3Object obj : page.contents()) {
                    objectKeys.add(S3_ACCESS_URL + "/" + encode(obj.key(), StandardCharsets.UTF_8));
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
