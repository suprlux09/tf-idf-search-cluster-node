## 분산 문서 검색

### 개요

- 본 프로젝트는 Michael Pogrebinsky의 'Distributed Systems & Cloud Computing with Java' 강의에서 제공되는 분산 문서 검색 코드를 AWS 환경에서 배포하는 것을 목표로 하였습니다.
- 프론트엔드 서버와 검색 클러스터를 컨테이너로 돌리기 위해 Dockerfile을 생성, Github push 시 도커 이미지를 ECR에 업로드하도록 설정했습니다.

### [프론트엔드 서버](https://github.com/suprlux09/tf-idf-search-frontend)
```
ZOOKEEPER_ADDRESS=
DOCUMENTS_LOCATION=  # 검색 클러스터의 S3_ACCESS_URL와 동일
```

프론트엔드 서버는 사용자에게 보여줄 웹 페이지를 제공하며, 사용자의 검색 쿼리 요청을 검색 클러스터의 leader 노드에 보내고, 결과를 받아오는 역할을 수행합니다.

- ECS Fargate에서 컨테이너로 구동, ALB가 사용자 요청을 컨테이너들로 분산합니다.
- 사용자의 검색 요청이 들어오면, Zookeeper Ensemble로부터 모든 leader 노드의 주소를 가져오고, 그 중 하나를 무작위로 선택하여 요청을 보냅니다.

### Zookeeper Ensemble

Apache Zookeeper는 분산 시스템에서 구성 요소들 간의 상호작용을 매개하는 코디네이션을 수행하는 프로그램입니다. 본 프로젝트에서는 가용성을 위해 클러스터 구조인 Zookeeper Ensemble을 구성하였습니다.

- EC2 3개로 Zookeeper Ensemble을 구성, NLB가 Zookeeper Ensemble로 들어오는 요청을 각 서버로 분산합니다.
- znode를 생성하고, 해당 znode 아래에서 검색 클러스터의 leader, worker의 주소 등 정보를 관리하도록 하였습니다.

    ```
    /[id]: 상위 클러스터 znode
    /[id]/coordinators_service_registry: leader 노드 주소 정보
    /[id]/workers_service_registry: worker 노드 주소 정보
    /[id]/election: leader election 시 사용
    ```

  ![zookeeper status.png](https://file.notion.so/f/f/5256c0a5-a551-478f-bf50-9e1f13c8d1d7/6760da27-51fa-44b3-8cdc-2fd9343f9a9b/zookeeper_status.png?table=block&id=117ebfda-82a3-80b3-90ab-edf50addfb9e&spaceId=5256c0a5-a551-478f-bf50-9e1f13c8d1d7&expirationTimestamp=1732766400000&signature=3eKUVjN9lmqAMhx822qeShFFA6t-dBGLpkHjkcJBgbI&downloadName=zookeeper+status.png)


- znode의 `[id]`
    - ECS service에 클러스터가 대응되는 경우, service deployment id
    - ECS task에 클러스터가 대응되는 경우, TaskARN

### 검색 클러스터

```
ZOOKEEPER_ADDRESS=
S3_ACCESS_URL=
S3_BUCKET_NAME=
S3_ACCESS_KEY_ID=
S3_SECRET_ACCESS_KEY=
ECS_ACCESS_KEY_ID=
ECS_SECRET_ACCESS_KEY=
```

하나의 검색 클러스터는 leader 노드 하나와 나머지 worker 노드로 구성됩니다. 노드가 클러스터에 속하게 되면 leader election을 수행하여 각자의 역할이 결정됩니다. leader 노드는 검색 쿼리 요청을 받고, 검색에 사용되는 문서를 분할하여 worker들에게 작업을 분산합니다. 작업들이 완료되면 leader는 결과를 취합해서 프론트엔드 서버로 보냅니다. 검색 결과는 TF-IDF 가중치 값을 기준으로 정렬됩니다.

노드는 컨테이너로 실행되며, 여러 노드로 구성된 노드들의 집합인 클러스터를 service 또는 task로 설정할 수 있습니다.

### 검색 클러스터를 service에 대응

![cluster-service.drawio.png](https://file.notion.so/f/f/5256c0a5-a551-478f-bf50-9e1f13c8d1d7/0e0911ce-455b-484a-842f-66159846c512/cluster-service.drawio.png?table=block&id=121ebfda-82a3-8048-b82b-dcadff5270db&spaceId=5256c0a5-a551-478f-bf50-9e1f13c8d1d7&expirationTimestamp=1732766400000&signature=kb1CjwfHBRsQ2MrVnRuN5HCPy30Cbwxcg6UGl5rd5FY&downloadName=cluster-service.drawio.png)

- ECS Fargate에서 컨테이너로 구동, 검색 클러스터 하나는 ECS의 service 하나와 대응됩니다. service의 task는 하나의 컨테이너만을 가지게 됩니다.
- Fargate의 awsvpc 네트워크 모드에서 각 컨테이너는 독립적으로 IP 주소를 부여받습니다. 이 주소는 컨테이너 내부에서 네트워크 인터페이스 `eth1`에 바인딩되므로, 해당 네트워크 인터페이스에 바인딩된 IP 주소를 가져와서 Zookeeper에 등록하도록 구현했습니다.
- ECS service가 시작되고 구동된 최초의 컨테이너는 service의 deployment id로 Zookeeper에 새로운 znode를 등록해야 합니다. Task객체로부터 deployment id를 가져온 후 znode를 생성하도록 구현했습니다.
- 노드가 오토스케일링되는 구조로, 검색 처리 시간을 단축하는 데 유리하며, 문서 수가 유동적으로 증가하는 시나리오에 적합합니다.

### 검색 클러스터를 task에 대응

![cluster_task.drawio.png](https://file.notion.so/f/f/5256c0a5-a551-478f-bf50-9e1f13c8d1d7/fa3950f5-0e0d-4f85-ac36-6d0d73aa2a67/cluster_task.drawio.png?table=block&id=121ebfda-82a3-80b3-946c-f43ff814a4db&spaceId=5256c0a5-a551-478f-bf50-9e1f13c8d1d7&expirationTimestamp=1732766400000&signature=I_81HxIfTLSZmJWJIs5IoPr-Dn35LA2jZPybZ9l1bu4&downloadName=cluster_task.drawio.png)

- ECS Fargate에서 검색 클러스터 하나는 service의 task 하나와 대응됩니다. 클러스터에 속하는 노드들은 하나의 task 안에서 여러 개의 컨테이너로 실행됩니다.
- 컨테이너들은 동일한 네트워크 인터페이스를 공유하고, 서로 다른 포트를 부여받습니다.
- ECS service가 시작되고 구동된 최초의 컨테이너는 TaskARN으로 Zookeeper에 새로운 znode를 등록해야 합니다. ECS 작업 메타데이터로부터 TaskARN을 가져올 수 있습니다.
- 클러스터가 오토스케일링되는 구성이므로, 더 많은 트래픽을 병렬적으로 처리하는 데 유리한 구성입니다. 사용자 접속이 유동적으로 증가하는 시나리오에 적합합니다.