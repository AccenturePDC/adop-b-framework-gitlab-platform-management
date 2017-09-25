/**
 * Author: john.bryan.j.sazon@accenture.com
 */
import jenkins.model.*
import com.cloudbees.plugins.credentials.*
import hudson.util.Secret
import com.dabsquared.gitlabjenkins.connection.*

/**
 * Get environment variables
 */
def env = System.getenv()
def gitlabHost = env['GITLAB_HTTP_URL']
def gitlabToken = env['GITLAB_TOKEN'] ?: new File('/tmp/gitlab_token_file').text.trim()
def gitlabConnectionName = "ADOP Gitlab"
if (!gitlabToken || !gitlabHost) {
    println "gitlabToken or gitlabHost is null, credentials and gitlab connection setup will not proceed."
    return
}
def credentialDescription = "ADOP Gitlab Integration token"
def credentialsId = "gitlab-secrets-id"
def instance = Jenkins.getInstance()
def systemCredentialsProvider = SystemCredentialsProvider.getInstance()
def credentialScope = CredentialsScope.GLOBAL
def credentialDomain = com.cloudbees.plugins.credentials.domains.Domain.global()
def credentialToCreate = new GitLabApiTokenImpl(credentialScope, credentialsId, credentialDescription, Secret.fromString(gitlabToken))

/**
 * Check if credentials with @credentialsId already exists and
 * removeCredentials the @credentialsId if it exists.
 */
systemCredentialsProvider.getCredentials().each {
  credentials = (com.cloudbees.plugins.credentials.Credentials) it
  if (credentials.getDescription() == credentialDescription) {
    println "Found existing credentials: " + credentialDescription
    systemCredentialsProvider.removeCredentials(credentialDomain,credentialToCreate)
    println credentialDescription + " is removed and will be recreated.."
  }
}

/**
 * Create the credentials
 */
println "--> Registering Gitlab API token.."
systemCredentialsProvider.addCredentials(credentialDomain,credentialToCreate)
println credentialDescription + " created.."

/**
 * Create/Update Gitlab connection settings
 * Reference: https://groups.google.com/forum/#!topic/jenkinsci-dev/NYPGvrVolak
 */
GitLabConnectionConfig descriptor = (GitLabConnectionConfig) instance.getDescriptor(GitLabConnectionConfig.class)
GitLabConnection gitLabConnection = new GitLabConnection(gitlabConnectionName, gitlabHost, credentialsId, false, 10, 10)
descriptor.getConnections().clear()
descriptor.addConnection(gitLabConnection)
descriptor.save()