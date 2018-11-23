def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    ENV = config.env
    VPC = config.vpc
    USER_UNIQUE_NAME = config.user_unique_name

    pipeline {
        agent any

        options {
            disableConcurrentBuilds()
        }

        stages {
            stage("terraform-plan-and-apply") {
                steps {
                    script {
                        sh 'rm -rf terraform-repo'
                        dir('terraform-repo') {
                            git url: "git@github.com:${GITHUB_USER_OR_ORG}/bifrost-infra-provisioner.git"

                            dir('terraform/alb-and-dns') {
                                setupPythonVirtualEnv()

                                def backendConfigPath = populateBackendConfigFile(
                                        ENV,
                                        USER_UNIQUE_NAME,
                                        clusterName
                                )

                                populateTerraformTfvars(
                                        ENV,
                                        VPC,
                                        USER_UNIQUE_NAME,
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

def setupPythonVirtualEnv() {
    sh """
        virtualenv venv
        . venv/bin/activate
        pip install -r requirements.txt
        deactivate
       """
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

def populateTerraformTfvars(env, vpc, userUniqueName, backendConfigPath) {
    varFile = "${backendConfigPath}/terraform.tfvars"
    vars = [
            terraformStringVar("env", env),
            terraformStringVar("vpc_name", vpc),
            terraformStringVar("unique_name", userUniqueName)
    ]
    writeFile encoding: "UTF-8", file: "${varFile}", text: vars.join("\n")
}

def terraformStringVar(key, value) {
    return "${key} = " + """ "${value}" """
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