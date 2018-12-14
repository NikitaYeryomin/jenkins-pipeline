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
					sleep(500)
					if ( currentEnvironment == 'QA' ) {
						// global jenkins vars
						sh 'env'
						def branch = env.BRANCH_NAME
						def environment = env.ENVIRONMENT
						// meta vars
						version = 1.0
						commitHash = sh(returnStdout: true, script: "git rev-parse HEAD")
						buildExecutor = new DockerExecutor(this, [
								repo                 : "ae/infra/jenkins/agent/linux",
								image                : "257540276112.dkr.ecr.us-east-1.amazonaws.com/nikita-test:${commitHash}",
								region               : "us-east-1",
								registryAuthenticator: new AwsECRAuthenticator(this, "us-east-1"),
								buildArgs            : [
										BUILD_NUMBER: "${env.JOB_NAME}#${env.BUILD_NUMBER}",
										COMMIT_HASH : commitHash
								]
						])
					} else if ( currentEnvironment == 'PROD' ) {
						// Create an Artifactory server instance, as described above in this article:
						def server = Artifactory.server 'my-server-id'
						// If the docker daemon host is not specified, "/var/run/dokcer.sock" is used as a default value:
						def rtDocker = Artifactory.docker server: server
					}
				}
			}
		}
		stage("Initialize") {
			steps {
				script {
					if ( currentEnvironment == 'QA' ) {
						buildExecutor.init("docker")
					} else if ( currentEnvironment == 'PROD' ) {
						// Attach custom properties to the published artifacts:
						rtDocker.addProperty("project-name", "docker1").addProperty("status", "stable")
					}
				}
			}
		}
		stage("Build") {
			steps {
				script {
					if ( currentEnvironment == 'QA' ) {
						buildExecutor.execute()
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
						// Push a docker image to Artifactory (here we're pushing hello-world:latest). The push method also expects
						// Artifactory repository name (<target-artifactory-repository>).
						// Please make sure that <artifactoryDockerRegistry> is configured to reference the <target-artifactory-repository> Artifactory repository. In case it references a different repository, your build will fail with "Could not find manifest.json in Artifactory..." following the push.
						def buildInfo = rtDocker.push '<artifactory-docker-registry-url>/hello-world:latest', '<target-artifactory-repository>'
						// Publish the build-info to Artifactory:
						server.publishBuildInfo buildInfo
					}
				}
			}
		}
	}
}
