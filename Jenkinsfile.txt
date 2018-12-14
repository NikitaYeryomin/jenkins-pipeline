import com.ae.jenkins.executors.DockerExecutor
import com.ae.jenkins.authenticators.AwsECRAuthenticator
import com.ae.jenkins.executors.TerraformExecutor


def commitHash
def version
def buildExecutor

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
					// global jenkins vars
					sh 'env'
					def branch = env.BRANCH_NAME
					def environment = env.ENVIRONMENT

					// meta vars
					version = 1.0
					commitHash = sh(returnStdout: true, script: "git rev-parse HEAD")

					buildExecutor = new DockerExecutor(this, [
						repo: "ae/infra/jenkins/agent/linux",
						image: "257540276112.dkr.ecr.us-east-1.amazonaws.com/ae/infra/jenkins/agent/linux:${commitHash}",
						region: "us-east-1",
						registryAuthenticator: new AwsECRAuthenticator(this, "us-east-1"),
						buildArgs: [
						  BUILD_NUMBER: "${env.JOB_NAME}#${env.BUILD_NUMBER}",
						  COMMIT_HASH: commitHash
						]
					])
				}
			}
		}
		
		stage("Initialize") {
			steps {
				script {
					buildExecutor.init("docker")
				}
			}
		}

		stage("Build") {
			steps {
				script {
					buildExecutor.execute()
				}
			}
		}

		stage("Publish") {
			steps {
				script {
					buildExecutor.push()
				}
			}
		}

		stage("Deploy") {
			steps {
				script {
					def terraform = new TerraformExecutor(this)
					terraform.apply("service", [
					  region: "us-east-1",
					  resourcePrefix: "ae",
					  type: "infra",
					  name: "jenkins_linux_agent",
					  version: version,
					  image: "257540276112.dkr.ecr.us-east-1.amazonaws.com/ae/infra/jenkins/agent/linux:${commitHash}",
					  credentials: [
						ssh: "github-ssh-key"
					  ]
					])

					def rtServer = Artifactory.server "test"
					def rtDocker = Artifactory.docker server: rtServer
					def buildInfo = Artifactory.newBuildInfo()
					def dockerTag = "docker/ae/infra/jenkins/agent/linux:${commitHash}"

					buildInfo = rtDocker.push(dockerTag, "docker", buildInfo)
					println "Docker Buildinfo"
					rtServer.publishBuildInfo buildInfo
				}
			}
		}
	}
}
