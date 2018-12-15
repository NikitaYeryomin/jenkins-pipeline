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
			steps {
				script {
				    println('-== Building image for ' + currentEnvironment + ' environment ==-')
                    //sleep in seconds
                    sleep(5)
				    configFileProvider([configFile(fileId: 'pipeline-config', variable: 'pipeline_config')]) {
                        println('reading pipeline-config: ' + pipeline_config)
                        def config = readJSON file: pipeline_config
                        println('docker_repo: ' + config['docker_repo'])
                        println('docker_image: ' + config['docker_image'])
                        println('aws_region: ' + config['aws_region'])

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
                            def server = Artifactory.server 'artifactorymaster'
                            def rtDocker = Artifactory.docker username:'jenkinsapp2', password:'25f4s2s67Op7', server: server
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
						sh "docker build -t artifactory.aesansun.com/dockervirtualappcicdl/elytestappl:${commitHash} ."
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
						buildInfo = rtDocker.push("artifactory.aesansun.com/dockervirtualappcicdl/elytestappl:${commitHash}",'docker-local')
					}
				}
			}
		}
	}
}
