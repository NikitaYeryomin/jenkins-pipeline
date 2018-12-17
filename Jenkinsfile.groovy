import com.ae.jenkins.executors.DockerExecutor
import com.ae.jenkins.authenticators.AwsECRAuthenticator

def commitHash
def version
def buildExecutor
def currentEnvironment = env.ENVIRONMENT

pipeline {
	agent { label 'default'}
	options {
		timestamps()
		ansiColor('xterm')
	}
	stages {
		stage("Init") {
		    environment {
                ARTIFACTORY_CREDS = credentials('jenkins-artifactory-creds')
            }
			steps {
				script {
				    println('-== Building image for ' + currentEnvironment + ' environment ==-')
                    //sleep in seconds
                    sleep(5)
				    configFileProvider([configFile(fileId: 'pipeline-config', variable: 'pipeline_config')]) {
                        println('reading pipeline-config: ' + pipeline_config)
                        def config = readJSON file: pipeline_config
                        if ( currentEnvironment == 'QA' ) {
                            println('docker_repo: ' + config['docker_repo'])
                            println('docker_image: ' + config['docker_image'])
                            println('aws_region: ' + config['aws_region'])
                        } else if ( currentEnvironment == 'PROD' ) {
                            println('Artifactory server: ' + config['artifactory_server'])
                            println('Artifactory image: ' + config['artifactory_image'])
                        }

                        commitHash = sh(returnStdout: true, script: "git rev-parse HEAD")
                        if ( currentEnvironment == 'QA' ) {
                            // global jenkins vars
                            sh 'env'
                            def branch = env.BRANCH_NAME
                            def environment = env.ENVIRONMENT
                            // meta vars
                            version = 1.0
                            buildExecutor = new DockerExecutor(this, [
                                    repo                 : "${config['docker_repo']}",
                                    image                : "${config['docker_image']}:${commitHash}",
                                    region               : "${config['aws_region']}",
                                    registryAuthenticator: new AwsECRAuthenticator(this, "${config['aws_region']}"),
                                    buildArgs            : [
                                            BUILD_NUMBER: "${env.JOB_NAME}#${env.BUILD_NUMBER}",
                                            COMMIT_HASH : commitHash
                                    ]
                            ])
                        } else if ( currentEnvironment == 'PROD' ) {
                            def server = Artifactory.server config['artifactory_server']
                            def rtDocker = Artifactory.docker username:ARTIFACTORY_CREDS_USR, password:ARTIFACTORY_CREDS_PSW, server: server
                        }
					}
				}
			}
		}
		stage("Initialize") {
			steps {
				script {
					if (currentEnvironment == 'QA') {
						buildExecutor.init("docker")
					}
				}
			}
		}
		stage("Build") {
			steps {
				script {
					if (currentEnvironment == 'QA') {
						buildExecutor.execute()
					} else if (currentEnvironment == 'PROD') {
						sh "docker build -t ${config['artifactory_image']}:${commitHash} ."
					}
				}
			}
		}
		stage("Publish") {
			steps {
				script {
					if ( currentEnvironment == 'QA' ) {
						buildExecutor.push()
					} else if ( currentEnvironment == 'PROD' ) {
						println("Pushing Docker image to the Artifactory server")
						buildInfo = rtDocker.push("${config['artifactory_image']}:${commitHash}",'docker-local')
					}
				}
			}
		}
	}
}
