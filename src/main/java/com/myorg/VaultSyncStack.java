package com.myorg;

import software.constructs.Construct;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import software.amazon.awscdk.CfnParameter;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
public class VaultSyncStack extends Stack {
    public VaultSyncStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public VaultSyncStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
        try {
                String region = System.getenv("CDK_DEFAULT_REGION");
                String accountNumber = System.getenv("CDK_DEFAULT_ACCOUNT");

                //CF parameters from the command line         
                CfnParameter emailAddress = new CfnParameter(this, "email");
                CfnParameter prefix = new CfnParameter(this,"prefix");
                CfnParameter secretPrefix = new CfnParameter(this,"secretPrefix");
                CfnParameter vaultIP = new CfnParameter(this,"ip");
                CfnParameter vaultPort = new CfnParameter(this, "port");
                CfnParameter token = new CfnParameter(this,"token");

                System.out.println(accountNumber);
                System.out.println(region);

                // AWS KMS
                Key kmsKey = Key.Builder.create(this,"replicate-kms-key")
                                        .alias("replicate-vault-key")
                                        .build();
                
                //AWS SNS topics

                Topic successTopic = Topic.Builder.create(this,"successVaultReplication")
                                                .topicName("successVaultReplication")
                                                .build();
                Topic failedTopic = Topic.Builder.create(this,"failedVaultReplication")
                                                .topicName("failedVaultReplication")
                                                .build();                                                                

                successTopic.addSubscription(new EmailSubscription(emailAddress.getValueAsString()));
                failedTopic.addSubscription(new EmailSubscription(emailAddress.getValueAsString()));

                //Env vars for the AWS lambda
                Map<String, String> environmentVariables = new HashMap<>();
                environmentVariables.put("failedTopicArn",failedTopic.getTopicArn());
                environmentVariables.put("successTopicArn",successTopic.getTopicArn());
                environmentVariables.put("kms_key_arn",kmsKey.getKeyArn());
                environmentVariables.put("prefix",prefix.getValueAsString());
                environmentVariables.put("region",region);
                environmentVariables.put("secretPrefix",secretPrefix.getValueAsString());
                environmentVariables.put("vault_ip",vaultIP.getValueAsString());
                environmentVariables.put("vault_port",vaultPort.getValueAsString());
                environmentVariables.put("vault_token",token.getValueAsString());

                //AWS IAM policy statements
                PolicyStatement statement1 = PolicyStatement.Builder.create()
                                            .effect(Effect.ALLOW)
                                            .actions(Arrays.asList(new String[]{"logs:CreateLogGroup"}))
                                            .resources(Arrays.asList(new String[]{"arn:aws:logs:"+region+":"+accountNumber+":*"}))
                                            .build();                             
                PolicyStatement statement2 = PolicyStatement.Builder.create()
                                            .effect(Effect.ALLOW)
                                            .actions(Arrays.asList(new String[]{"logs:CreateLogStream","logs:PutLogEvents"}))
                                            .resources(Arrays.asList(new String[]{"arn:aws:logs:"+region+":"+accountNumber+"log-group:/aws/lambda/replicate-vault:*"}))
                                            .build();                             
                
                PolicyStatement statement3 = PolicyStatement.Builder.create()
                                            .effect(Effect.ALLOW)
                                            .actions(Arrays.asList(new String[]{"secretsmanager:GetSecretValue","secretsmanager:DescribeSecret","secretsmanager:PutSecretValue",
                                                                                "secretsmanager:CreateSecret","secretsmanager:ListSecretVersionIds","secretsmanager:ListSecrets",
                                                                                "secretsmanager:UpdateSecret","secretsmanager:TagResource","secretsmanager:UntagResource",
                                                                                "secretsmanager:PutResourcePolicy"}))
                                            .resources(Arrays.asList(new String[]{"*"}))
                                            .build();        
                PolicyStatement statement4 = PolicyStatement.Builder.create()
                                            .effect(Effect.ALLOW)
                                            .actions(Arrays.asList(new String[]{"sns:Publish"}))
                                            .resources(Arrays.asList(new String[]{"arn:aws:sns:"+region+":"+accountNumber+":"+successTopic.getTopicName(),
                                                                                "arn:aws:sns:"+region+":"+accountNumber+":"+failedTopic.getTopicName()}))
                                            .build();      

                                    
                //AWS IAM policy document
                PolicyDocument policyDocument = PolicyDocument.Builder.create()
                                                .statements(Arrays.asList(new PolicyStatement[]{statement1,statement2,statement3,statement4}))
                                                .build();
    

                //AWS IAM role
                Role vaultReplicaRole = Role.Builder.create(this, "replicate-lambda-role")
                                        .roleName("replicate-lambda-role")
                                        .assumedBy(new ServicePrincipal("lambda.amazonaws.com")).build();
                                        

                //AWS Lambda
                Function replicateVault = Function.Builder.create(this,"replicate-vault")
                                                .runtime(Runtime.PYTHON_3_7)
                                                .code(Code.fromAsset("lambda"))
                                                .handler("lambda_function.lambda_handler")
                                                .environment(environmentVariables)
                                                .functionName("replicate-vault")
                                                .role(vaultReplicaRole)
                                                .build();

                    
        } catch (Exception e) {
            e.printStackTrace();
        }

    }    
}
