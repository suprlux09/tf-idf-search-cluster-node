## Distributed Document Search

### Overview

This project aims to deploy distributed document search code provided in Michael Pogrebinsky's 'Distributed Systems & Cloud Computing with Java' course in an AWS environment.

- Created Dockerfiles to run the frontend server and search cluster as containers, and configured GitHub push to upload Docker images to ECR.

### [Frontend Server](https://github.com/suprlux09/tf-idf-search-frontend)
```
ZOOKEEPER_ADDRESS=
DOCUMENTS_LOCATION=  # Same as the search cluster's S3_ACCESS_URL
```

The frontend server provides web pages to users, sends user search query requests to the search cluster's leader node, and retrieves results.

- Runs as containers on ECS Fargate, with ALB distributing user requests across containers.
- When a search request comes in, it retrieves all leader node addresses from the Zookeeper Ensemble and randomly selects one to send the request to.

### Zookeeper Ensemble

Apache Zookeeper is a program that performs coordination mediating interactions between components in distributed systems. In this project, a Zookeeper Ensemble cluster structure was configured for availability.

- Configured Zookeeper Ensemble with 3 EC2 instances, with NLB distributing incoming requests to each server.
- Created znodes and managed information such as search cluster leader and worker addresses under the corresponding znode.

    ```
    /[id]: Parent cluster znode
    /[id]/coordinators_service_registry: Leader node address information
    /[id]/workers_service_registry: Worker node address information
    /[id]/election: Used for leader election
    ```

  ![zookeeper status.png](https://file.notion.so/f/f/5256c0a5-a551-478f-bf50-9e1f13c8d1d7/6760da27-51fa-44b3-8cdc-2fd9343f9a9b/zookeeper_status.png?table=block&id=117ebfda-82a3-80b3-90ab-edf50addfb9e&spaceId=5256c0a5-a551-478f-bf50-9e1f13c8d1d7&expirationTimestamp=1732766400000&signature=3eKUVjN9lmqAMhx822qeShFFA6t-dBGLpkHjkcJBgbI&downloadName=zookeeper+status.png)


- znode's `[id]`
    - For clusters corresponding to ECS service: service deployment id
    - For clusters corresponding to ECS task: TaskARN

### Search Cluster

```
ZOOKEEPER_ADDRESS=
S3_ACCESS_URL=
S3_BUCKET_NAME=
S3_ACCESS_KEY_ID=
S3_SECRET_ACCESS_KEY=
ECS_ACCESS_KEY_ID=
ECS_SECRET_ACCESS_KEY=
```

One search cluster consists of one leader node and the remaining worker nodes. When a node joins the cluster, leader election is performed to determine each node's role. The leader node receives search query requests and distributes the work by partitioning the documents used for searching among workers. Once the tasks are completed, the leader aggregates the results and sends them to the frontend server. Search results are sorted based on TF-IDF weight values.

Nodes run as containers, and clusters, which are collections of multiple nodes, can be configured as either services or tasks.

### Mapping Search Cluster to Service

![cluster-service.drawio.png](https://file.notion.so/f/f/5256c0a5-a551-478f-bf50-9e1f13c8d1d7/0e0911ce-455b-484a-842f-66159846c512/cluster-service.drawio.png?table=block&id=121ebfda-82a3-8048-b82b-dcadff5270db&spaceId=5256c0a5-a551-478f-bf50-9e1f13c8d1d7&expirationTimestamp=1732766400000&signature=kb1CjwfHBRsQ2MrVnRuN5HCPy30Cbwxcg6UGl5rd5FY&downloadName=cluster-service.drawio.png)


- Runs as containers on ECS Fargate, where one search cluster corresponds to one ECS service. Service tasks will have only one container.
- In Fargate's awsvpc network mode, each container is independently assigned an IP address. This address is bound to the network interface `eth1` inside the container. The IP address bound to that network interface is retrieved and registered with Zookeeper.
- The first container launched when the ECS service starts must register a new znode in Zookeeper with the service's deployment id. The deployment id is retrieved from the Task object, and it is used to create the znode.
- The node auto-scaling structure is advantageous for reducing search processing time and is suitable for scenarios where the number of documents increases dynamically.

### Mapping Search Cluster to Task

![cluster_task.drawio.png](https://file.notion.so/f/f/5256c0a5-a551-478f-bf50-9e1f13c8d1d7/fa3950f5-0e0d-4f85-ac36-6d0d73aa2a67/cluster_task.drawio.png?table=block&id=121ebfda-82a3-80b3-946c-f43ff814a4db&spaceId=5256c0a5-a551-478f-bf50-9e1f13c8d1d7&expirationTimestamp=1732766400000&signature=I_81HxIfTLSZmJWJIs5IoPr-Dn35LA2jZPybZ9l1bu4&downloadName=cluster_task.drawio.png)


- In ECS Fargate, one search cluster corresponds to one task of the service. The nodes belonging to the cluster run as multiple containers within a single task.
- Containers share the same network interface and are assigned different ports.
- The first container launched when the ECS service starts must register a new znode in Zookeeper with the TaskARN. The TaskARN can be retrieved from the ECS task metadata.
- The cluster auto-scaling structure is advantageous for parallel processing of higher traffic loads and is suitable for scenarios where user connections increase dynamically.