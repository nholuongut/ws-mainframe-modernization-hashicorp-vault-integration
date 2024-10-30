import json
import os
import boto3
import requests
from pprint import pprint

# This program does the HashiCorp Vault synchronization with AWS Secrets Manager

region = os.environ['AWS_REGION']
kms_key_arn = os.environ['kms_key_arn']
secretPrefix = os.environ['secretPrefix']
prefix = os.environ['prefix']
vault_ip = os.environ['vault_ip']
vault_port = os.environ['vault_port']
vault_token = os.environ['vault_token'] 
successTopicArn = os.environ['successTopicArn']
failedTopicArn = os.environ['failedTopicArn']

session = boto3.session.Session()

client = session.client(
        service_name='secretsmanager',
        region_name=region,
    )

snsClient = boto3.client('sns')


def getExistingSecretsFromVault()->dict:
        headers = {'X-Vault-Token':vault_token}
        result = dict()
        url = f'https://{vault_ip}:{vault_port}/v1/{secretPrefix}/data/{prefix}'
        r = requests.get(url, headers=headers)
        if r.ok:
            resp_dict = r.json()
            if resp_dict['data']['data']:
                result= resp_dict['data']['data']
            if resp_dict['data']['metadata']:
                result['version'] = resp_dict['data']['metadata']['version']
        else:
            raise Exception(r.status_code,r.content,r.reason)
        return result
    
def createUpdateSecretsInSM(existing_Vault_secrets_dt:dict):
    
    # check if prefix exists in SM
    vaultSecretVersion = existing_Vault_secrets_dt.get('version')
    resp = client.list_secrets(Filters=[{'Key':'name','Values':[prefix]}])
    result = []
    if not resp['SecretList']:
        secretArn=createSecretInSM(existing_Vault_secrets_dt)
        addResourcePolicyToSecretInSM(secretArn)
    elif resp['SecretList']:
        sl = resp['SecretList']
        for secret in sl:
       
            if not secret.get('Tags'):
                updateSecretInSM(existing_Vault_secrets_dt)
                continue
        
            for tag in secret.get('Tags'):
 
                if 'version' == tag.get('Key') and tag.get('Value') != vaultSecretVersion:
                    updateSecretInSM(existing_Vault_secrets_dt)
                else:
                     updateSecretInSM(existing_Vault_secrets_dt)


    return

def createSecretInSM(secrets_dt:dict)->str:
    secretStringJson=secrets_dt.copy()
    version=str(secretStringJson.pop('version'))
    secretStringJson=json.dumps(secretStringJson)
     
    resp=client.create_secret(Name=prefix,
                        Description=prefix,
                        SecretString=secretStringJson,
                        KmsKeyId=kms_key_arn,
                        Tags=[{'Key':'version','Value':version}])
 
    return resp['ARN']

def addResourcePolicyToSecretInSM(secretArn:str):
    policy='{\n"Version":"2012-10-17",\n"Statement":[{\n"Effect":"Allow",\n"Principal":{\n"Service":"m2.amazonaws.com"\n},\n"Action":"secretsmanager:GetSecretValue",\n"Resource":"*"\n}]\n}'
    resp=client.put_resource_policy(SecretId=secretArn,
                                    ResourcePolicy=policy
                                    )
    return

def updateSecretInSM(secrets_dt:dict):
    secretStringJson=secrets_dt.copy()
    newVersion=str(secretStringJson.pop('version'))
    secretStringJson=json.dumps(secretStringJson)
       
    #update secrets
    client.update_secret(SecretId=prefix,
                        Description=prefix,
                        SecretString=secretStringJson,
                        KmsKeyId=kms_key_arn
                        )
    #remove existing version tag
    resp=client.untag_resource(SecretId=prefix,
                          TagKeys=['version']
                          )
   
    #remove existing version tag
    resp=client.tag_resource(SecretId=prefix,
                          Tags=[
                              {'Key':'version',
                               'Value':newVersion
                              }]
                          )
   
    return

def sendFailedSNSNotification(exception):
    try:
        snsClient.publish(TopicArn=failedTopicArn,
                Message=exception,
                Subject='Replicating Vault run failed')
    except Exception as e:
        raise Exception(e)
    return

def sendSuccessSNSNotification():
    snsClient.publish(TopicArn=successTopicArn,
                      Message='Hello \n Replicating the Vault run was successfull',
                      Subject='Replicating Vault success')
    return
      
        

def lambda_handler(event, context):
    
    try:

        #get the list of existing secrets in vault
        existing_Vault_secrets_dt = getExistingSecretsFromVault()
        
        #create/update secrets in SM:
        createUpdateSecretsInSM(existing_Vault_secrets_dt)
        
        #send success message to SNS
        sendSuccessSNSNotification()

    except Exception as e:
        exceptionMessage=json.dumps(f'Error due to : {e}')
        sendFailedSNSNotification(exceptionMessage)
        return {
           'statusCode': 500,
            'body': exceptionMessage
        }

    return {
        'statusCode': 200,
        'body': json.dumps('Successfully completed')
    }
