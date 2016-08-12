// Constants
def platformManagementFolderName= "/Platform_Management"
def platformManagementFolder = folder(platformManagementFolderName) { displayName('Platform Management') }

// Jobs
def loadPlatformExtensionJob = freeStyleJob(platformManagementFolderName + "/Load_Platform_Extension")
 
// Setup setup_cartridge
loadPlatformExtensionJob.with{
    wrappers {
        colorizeOutput('css')
        preBuildCleanup()
        sshAgent('adop-jenkins-master')
    }
    parameters{
      stringParam("GIT_URL",'',"The URL of the git repo for Platform Extension")
      stringParam("GIT_REF","master","The reference to checkout from git repo of Platform Extension. It could be a branch name or a tag name. Eg : master, 0.0.1 etc")
      credentialsParam("AWS_CREDENTIALS"){
        type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
        description('AWS access key and secret key for your account')
      }
    }
    scm{
      git{
        remote{
          url('${GIT_URL}')
          credentials("adop-jenkins-master")
        }
        branch('${GIT_REF}')
      }
    }
    label("aws")
    wrappers {
      preBuildCleanup()
      injectPasswords()
      maskPasswords()
      sshAgent("adop-jenkins-master")
      credentialsBinding {
        usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", '${AWS_CREDENTIALS}')
      }
    }
    steps {
        shell('''#!/bin/bash
export ANSIBLE_FORCE_COLOR=true
echo "This job loads the platform extension ${GIT_URL}"

# Source metadata
if [ -f ${WORKSPACE}/extension.metadata ]; then
    source ${WORKSPACE}/extension.metadata
fi

# Provision any EC2 instances in the AWS folder
if [ -d ${WORKSPACE}/service/aws ]; then
    
    if [ -f ${WORKSPACE}/service/aws/service.template ] && [ -f ${WORKSPACE}/service/aws/cf-runner.yml ]; then
        
        echo "#######################################"
        echo "Adding EC2 platform extension on AWS..."

        cd service/aws/
        ansible-playbook cf-runner.yml
        if [[ $? -gt 0 ]]; then exit 1; fi
        cd -

        if [ -f ${WORKSPACE}/service/aws/ec2-extension.conf ]; then
                        
            echo "#######################################"
            echo "Adding EC2 instance to NGINX config using xip.io..."
            
            export SERVICE_NAME="EC2-Service-Extension-${BUILD_NUMBER}"
            cp ${WORKSPACE}/service/aws/ec2-extension.conf ec2-extension.conf
            NODE_IP=$(cat ${WORKSPACE}/service/aws/instance_ip.txt)

            ## Add nginx configuration
            sed -i "s/###EC2_SERVICE_NAME###/${SERVICE_NAME}/" ec2-extension.conf
            sed -i "s/###EC2_HOST_IP###/${NODE_IP}/" ec2-extension.conf
            docker cp ec2-extension.conf proxy:/etc/nginx/sites-enabled/${SERVICE_NAME}.conf

            ## Reload nginx
            docker exec proxy /usr/sbin/nginx -s reload

            ## Don't stop jenkins to exit with error if nginx reload has failed.
            if [[ $? -gt 0 ]]; then
              echo "An error has been encountered while reloading nginx. There might be some upstreams that are not reachable."
              echo "Please run 'docker exec proxy /usr/sbin/nginx -s reload' to debug nginx."      
            fi

            echo "You can check that your EC2 instance has been succesfully proxied by accessing the following URL: ${SERVICE_NAME}.${PUBLIC_IP}.xip.io"
        else
            echo "INFO: /service/aws/ec2-extension.conf not found"
        fi
        
    else
        echo "INFO: /service/aws/service.template or /service/aws/cf-runner.yml not found."
    fi
fi

''')
    }
}
