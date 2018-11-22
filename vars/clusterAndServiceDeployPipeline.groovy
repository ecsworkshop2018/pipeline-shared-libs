def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    USER_UNIQUE_NAME = config.user_unique_name
    GITHUB_USER_OR_ORG = config.github_user_or_org

    pipeline {
        agent any

        options {
            disableConcurrentBuilds()
        }

        stages {
            stage("terraform-plan-and-apply") {
                steps {
                    script {
                        MAPPING = readYaml(file: "mapping.yaml")
                        ENV = MAPPING.env
                        VPC = MAPPING.vpc
                        ALB = MAPPING.alb
                        MAPPING.clusters.each { cluster ->
                            def clusterName = cluster.cluster_name
                            def serviceConfigs = cluster.services.collect { service -> getServiceConfig(service) }
                            def instanceType = getInstanceType(getLargestContainerMemory(serviceConfigs))

                            print("Instance min: " + getInstanceMin(instanceType, serviceConfigs))
                            print("Instance min: " + getInstanceMax(instanceType, serviceConfigs))

                            sh 'rm -rf terraform-repo'
                            dir('terraform-repo') {
                                git url: "git@github.com:${GITHUB_USER_OR_ORG}/bifrost-infra-provisioner.git"

                                dir('terraform/cluster-and-services') {
                                    setupPythonVirtualEnv()

                                    def backendConfigPath = populateBackendConfigFile(
                                            ENV,
                                            USER_UNIQUE_NAME,
                                            clusterName
                                    )

                                    populateTerraformTfvars(
                                            ENV,
                                            VPC,
                                            ALB,
                                            USER_UNIQUE_NAME,
                                            clusterName,
                                            instanceType,
                                            serviceConfigs,
                                            backendConfigPath
                                    )

                                    terraformPlan(backendConfigPath)

                                    terraformApply()

                                    commitBackendConfig(backendConfigPath)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

def setupPythonVirtualEnv() {
    sh """
        virtualenv venv
        . venv/bin/activate
        pip install -r requirements.txt
        deactivate
       """
}

String getInstanceType(largestContainerMemory) {
    memoryForOS = 100
    capacityFor2ContainersAndOS = (largestContainerMemory * 2) + memoryForOS
    if ( capacityFor2ContainersAndOS < 512 ) {
        return "t3.nano"
    } else if (capacityFor2ContainersAndOS < 1024 ) {
        return "t3.micro"
    } else {
        return "t3.small"
    }
}

int getInstanceCpuCapacity(instanceType) {
    switch(instanceType) {
        case "t3.nano":
            return 2048
        case "t3.micro":
            return 2048
        default:
            return 2048
    }
}

int getInstanceMemoryCapacity(instanceType) {
    switch(instanceType) {
        case "t3.nano":
            return 512
        case "t3.micro":
            return 1024
        default:
            return 2048
    }
}

int divideAndCeil(numerator, denominator) {
    final pythonContent = libraryResource('divide_and_ceil.py')
    writeFile(file: 'divide_and_ceil.py', text: pythonContent)
    result = sh (script: "python divide_and_ceil.py ${numerator} ${denominator}", returnStdout: true).trim()
    print(result)
    return result.toInteger()
}

int getInstanceMin(instanceType, serviceConfigs) {
    int totalMinContainers = getTotalMinContainers(serviceConfigs)
    return divideAndCeil(totalMinContainers, containersPerInstance(instanceType, serviceConfigs)) + 1
}

int getInstanceMax(instanceType, serviceConfigs) {
    int totalMaxContainers = getTotalMaxContainers(serviceConfigs)
    return divideAndCeil(totalMaxContainers, containersPerInstance(instanceType, serviceConfigs)) + 1
}

int containersPerInstance(instanceType, serviceConfigs) {
    int largestContainerMemory = getLargestContainerMemory(serviceConfigs)
    int largestContainerCpu = getLargestContainerCpu(serviceConfigs)
    int instanceMemory = getInstanceMemoryCapacity(instanceType)
    int instanceCpu = getInstanceCpuCapacity(instanceType)
    int containersPerInstanceMemory = instanceMemory.intdiv(largestContainerMemory)
    int containersPerInstanceCpu = instanceCpu.intdiv(largestContainerCpu)
    return containersPerInstanceCpu > containersPerInstanceMemory ? containersPerInstanceMemory : containersPerInstanceCpu
}

int getTotalMinContainers(serviceConfigs) {
    return sum(serviceConfigs.collect { serviceConfig -> serviceConfig.min_instances })
}

int getTotalMaxContainers(serviceConfigs) {
    return sum(serviceConfigs.collect { serviceConfig -> serviceConfig.max_instances })
}

int getLargestContainerMemory(serviceConfigs) {
    return max(serviceConfigs.collect { serviceConfig -> serviceConfig.memory })
}

int getLargestContainerCpu(serviceConfigs) {
    return max(serviceConfigs.collect { serviceConfig -> serviceConfig.cpu })
}

int max(intList) {
    return intList.inject(0, { result, i -> result > i ? result : i })
}

int sum(intList) {
    return intList.inject(0, { result, i -> result + i })
}

def getServiceConfig(service) {
    sh 'rm -rf service-repo'
    dir('service-repo') {
        git url: service.service_repo
        serviceConfig = readYaml(file: service.service_config_path)
        serviceConfig.docker_image = service.docker_image
        return serviceConfig
    }
}

def populateBackendConfigFile(env, userUniqueName, clusterName) {
    stateBucket = "ecs-workshop-terraform-state-${env}"
    backendConfigPath = "backend-configs/${env}-${userUniqueName}-${clusterName}"
    sh "mkdir -p ${backendConfigPath}"
    backendConfigFile = "${backendConfigPath}/backend.config"
    backenConfigs = [
            terraformStringVar("bucket", stateBucket),
            terraformStringVar("key", "${userUniqueName}-${clusterName}-cluster.tfstate")
    ]
    writeFile encoding: "UTF-8", file: "${backendConfigFile}", text: backenConfigs.join("\n")
    return "${backendConfigPath}"
}

def populateTerraformTfvars(env, vpc, alb, userUniqueName, clusterName, instanceType, serviceConfigs, backendConfigPath) {
    varFile = "${backendConfigPath}/terraform.tfvars"
    vars = [
            terraformStringVar("env", env),
            terraformStringVar("vpc_name", vpc),
            terraformStringVar("alb_name", alb),
            terraformStringVar("unique_name", userUniqueName),
            terraformStringVar("cluster_name", clusterName),
            terraformStringVar("instance_type", instanceType),
            terraformListVar("service_names", serviceConfigs.collect { serviceConfig -> serviceConfig.name }),
            terraformListVar("service_contexts", serviceConfigs.collect { serviceConfig -> serviceConfig.context }),
            terraformListVar("service_health_checks", serviceConfigs.collect { serviceConfig -> serviceConfig.health_check }),
            terraformListVar("service_memories", serviceConfigs.collect { serviceConfig -> serviceConfig.memory }),
            terraformListVar("service_cpus", serviceConfigs.collect { serviceConfig -> serviceConfig.cpu }),
            terraformListVar("docker_images", serviceConfigs.collect { serviceConfig -> serviceConfig.docker_image }),
            terraformListVar("service_min_instances", serviceConfigs.collect { serviceConfig -> serviceConfig.min_instances }),
            terraformListVar("service_max_instances", serviceConfigs.collect { serviceConfig -> serviceConfig.max_instances })
    ]
    writeFile encoding: "UTF-8", file: "${varFile}", text: vars.join("\n")
}

def terraformStringVar(key, value) {
    return "${key} = " + """ "${value}" """
}

def terraformListVar(key, values) {
    return "${key} = [" + values.collect({ value -> """ "${value}" """ }).join(",") + "]"
}

def terraformPlan(backendConfigPath) {
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
        sh """
            . venv/bin/activate
            TF_IN_AUTOMATION=true 
            terraform get -update=true
            terraform init -input=false -backend-config=${backendConfigPath}/backend.config
            terraform plan -input=false -out=terraform.tfplan -var-file=${backendConfigPath}/terraform.tfvars
            deactivate
           """
    }
}

def terraformApply() {
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
        sh """
            . venv/bin/activate
            TF_IN_AUTOMATION=true
            terraform apply -input=false terraform.tfplan
            deactivate
           """
    }
}

def commitBackendConfig(backendConfigPath) {
    sh """
        git add ${backendConfigPath}/backend.config
        git add ${backendConfigPath}/terraform.tfvars
        if [ ! \$(git status -s --untracked-files=no | wc -l) -eq 0 ]; then
            git commit -m "Backend config for applied terraform"
            git push origin master
        fi
       """
}