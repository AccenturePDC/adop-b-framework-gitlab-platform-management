// Constants
def platformManagementFolderName= "/Platform_Management"
def platformManagementFolder = folder(platformManagementFolderName) { displayName('Platform Management') }

// Jobs
def loadPlatformExtensionJob = freeStyleJob(platformManagementFolderName + "/Load_Openshift_Container_Platform")

// Get System variables
def env = System.getenv()
def keyPair = env['AWS_KEYPAIR'] ?: "your_aws_key_pair_here"
def vpcId = env['AWS_VPC_ID'] ?: "your_vpc_id_here"
def vpcCidr = env['AWS_VPC_CIDR'] ?: "your_vpc_cidr_here"
def publicSubnetId = env['DEFAULT_PUBLIC_SUBNET_ID'] ?: "your_vpc_subnet_id_here"
def privateSubnetId = env['DEFAULT_PRIVATE_SUBNET_ID'] ?: "your_vpc_subnet_id_here"
def amiId = env['DEFAULT_CENTOS_AMI'] ?: "region_centos_ami_id_here"
def awsRegion = env['AWS_REGION'] ?: "your_aws_region_id_here"

// Setup setup_cartridge
loadPlatformExtensionJob.with{
    wrappers {
        colorizeOutput('css')
        preBuildCleanup()
        sshAgent('openshift-cluster-ssh-key')
    }
    parameters{
        stringParam("CENTOS_AMI_ID","$amiId","")
        stringParam("AWS_REGION","$awsRegion","")
        stringParam("AWS_PUBLIC_SUBNET_ID","$publicSubnetId","")
        stringParam("AWS_PRIVATE_SUBNET_ID","$privateSubnetId","")
        stringParam("AWS_VPC_ID","$vpcId","")
        stringParam("AWS_VPC_CIDR","$vpcCidr","")
        stringParam("MASTERS_INSTANCE_TYPE",'t2.medium',"")
        stringParam("MINIONS_INSTANCE_TYPE",'t2.medium',"")
        stringParam("REMOTE_SUDO_USER",'centos',"")
        stringParam("OPENSHIFT_ADMIN_USER",'admin',"")
        nonStoredPasswordParam("OPENSHIFT_ADMIN_PASSWORD","")
        credentialsParam("AWS_CREDENTIALS"){
            type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
            description('AWS access key and secret key for your account')
        }
        stringParam("AWS_KEY_PAIR","$keyPair","")
        credentialsParam("AWS_SSH_CREDENTIALS"){
            type('com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey')
            description('AWS ssh access credentials to your Openshift EC2 cluster. This must be the private key content of AWS_KEY_PAIR')
        }
    }
    scm{
        git{
            remote{
                url('https://github.com/AccenturePDC/ansible-ec2-create.git')
            }
            branch('*/master')
        }
    }
    label("aws")
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent('${AWS_SSH_CREDENTIALS}')
        credentialsBinding {
            usernamePassword("EC2_ACCESS_KEY", "EC2_SECRET_KEY", '${AWS_CREDENTIALS}')
        }
    }
    steps {
        shell('''#!/bin/bash
#!/bin/bash

export ANSIBLE_FORCE_COLOR=true

# Set the EBS device name to be provisioned for Docker Volume group
DEVICE_NAME=/dev/xvdb

error_handler() {
  if [ $? -gt 0 ]
  then
    ansible-playbook terminate_instances.yml
    exit 1
  fi
}

echo "# -----------------------------------------------------------------"
echo "# Create Openshift Master EC2"
echo "# -----------------------------------------------------------------"

export MASTER_INSTANCE_NAME=Openshift_Master_Node
ROOT_VOLUME_SIZE=20
DOCKER_VG_VOLUME_SIZE=40
SUBNET=${AWS_PUBLIC_SUBNET_ID}
ansible-playbook provision.yml --extra-vars "instance_name=${MASTER_INSTANCE_NAME} aws_region=${AWS_REGION} key_name=${AWS_KEY_PAIR} vpc_subnet_id=${SUBNET} ami_id=${CENTOS_AMI_ID} instance_type=${MASTERS_INSTANCE_TYPE} volume_size=${ROOT_VOLUME_SIZE} lv_device_name=${DEVICE_NAME} lv_volume_size=${DOCKER_VG_VOLUME_SIZE} vpc_id=${AWS_VPC_ID} volume_device_name='/dev/sda1' env=openshift_cluster vpc_cidr_block=${AWS_VPC_CIDR}"

error_handler

echo "# -----------------------------------------------------------------"
echo "# Create Openshift Minion EC2"
echo "# -----------------------------------------------------------------"

export MINION_INSTANCE_NAME=Openshift_Minion_Node
ROOT_VOLUME_SIZE=20
DOCKER_VG_VOLUME_SIZE=40
SUBNET=${AWS_PRIVATE_SUBNET_ID}
ansible-playbook provision.yml --extra-vars "instance_name=${MINION_INSTANCE_NAME} aws_region=${AWS_REGION} key_name=${AWS_KEY_PAIR} vpc_subnet_id=${SUBNET} ami_id=${CENTOS_AMI_ID} instance_type=${MINIONS_INSTANCE_TYPE} volume_size=${ROOT_VOLUME_SIZE} lv_device_name=${DEVICE_NAME} lv_volume_size=${DOCKER_VG_VOLUME_SIZE} vpc_id=${AWS_VPC_ID} volume_device_name='/dev/sda1' env=openshift_cluster vpc_cidr_block=${AWS_VPC_CIDR}"

error_handler

echo "# -----------------------------------------------------------------"
echo "# Create the ansible host file for Openshift Ansible installation"
echo "# -----------------------------------------------------------------"

export $(cat instances_data.txt)

cat > openshift-hosts <<EOF
# Create an OSEv3 group that contains the masters and nodes groups
[OSEv3:children]
masters
nodes
# Set variables common for all OSEv3 hosts
[OSEv3:vars]
# SSH user, this user should allow ssh based auth without requiring a password
ansible_ssh_user=centos
# If ansible_ssh_user is not root, ansible_sudo must be set to true
ansible_sudo=true
deployment_type=origin
# uncomment the following to enable htpasswd authentication; defaults to DenyAllPasswordIdentityProvider
openshift_master_identity_providers=[{'name': 'htpasswd_auth', 'login': 'true', 'challenge': 'true', 'kind': 'HTPasswdPasswordIdentityProvider', 'filename': '/etc/origin/master/htpasswd'}]
# Use something like apps.<public-ip>.xip.io if you don't have a custom domain
openshift_master_default_subdomain=apps.${Openshift_Master_Node_PUBLIC_IP}.xip.io

[masters]
${Openshift_Master_Node_PUBLIC_HOSTNAME}
[nodes]
# Make the master node schedulable by default
${Openshift_Master_Node_PUBLIC_HOSTNAME} openshift_node_labels="{'region': 'infra', 'zone': 'default'}" openshift_schedulable=true
# You can add some nodes below if you want!
${Openshift_Minion_Node_PRIVATE_HOSTNAME} openshift_node_labels="{'region': 'primary', 'zone': 'default'}"
EOF

echo "# -----------------------------------------------------------------"
echo "# Install Openshift Prerequisite on Cluster"
echo "# -----------------------------------------------------------------"

git clone https://github.com/AccenturePDC/ansible-openshift-roles.git
cd ansible-openshift-roles
ansible-playbook -i ../openshift-hosts site.yml -e "device_name=${DEVICE_NAME}"

echo "# -----------------------------------------------------------------"
echo "# Install Openshift Cluster"
echo "# -----------------------------------------------------------------"

cd ../
git clone https://github.com/openshift/openshift-ansible -b master
cd openshift-ansible
yum -y install pyOpenSSL
ansible-playbook playbooks/byo/config.yml -u ${REMOTE_SUDO_USER} --become --become-user root -i ../openshift-hosts

echo "# -----------------------------------------------------------------"
echo "# Create initial cluster-admin user"
echo "# -----------------------------------------------------------------"

ansible masters -i ../openshift-hosts -m shell -a "htpasswd -b /etc/origin/master/htpasswd ${OPENSHIFT_ADMIN_USER} ${OPENSHIFT_ADMIN_PASSWORD}"
ansible masters -i ../openshift-hosts -m shell -a "oadm policy add-role-to-user cluster-admin ${OPENSHIFT_ADMIN_USER}"

''')
    }
}
