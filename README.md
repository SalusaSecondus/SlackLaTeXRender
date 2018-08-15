# SlackLaTeXRender
A simple SlackBot which runs in AWS and renders LaTeX equations for your channels.

## Compilation

This uses Maven so you should just be able to run `mvn package` in the root of the workspace and then upload `SlackLatexRenderer-1.0.0.jar` from the `target` directory.

## Setup

## AWS Setup (1)
1. Ensure you have a working AWS account
2. Select an AWS region (all further work is done in this region)
3. Create an S3 bucket in that region. (No special permissions are needed.)
4. Create a Lambda function
    1. Author from Scratch
        * Name = Whatever you like
        * Runtime = Java 8
        * Role = Create existing (use default)
    2. Main configuration
        * Handler = `com.nettgryppa.SlackLatexRenderer.LatexRenderingHandler`
        * Environment S3_REGION=(region selected above)
        * Environment BUCKET=(bucket created above)
        * Upload jar from compilation
        * (Make a note of the function ARN in the upper right)
        * SAVE
    3. "Add Trigger" - "API Gateway"
        * "Create new API"
        * Security = Open
        * ADD
        * Make a note of the API Endpoint (it's a URL)
5. Go to the IAM console and load the role you created earlier
    1. Substituting your bucket for `$BUCKET` and the function arn for `$LAMBDA_ARN`, add the following policy:
        ```
        {
            "Version": "2012-10-17",
            "Statement": [
              {
                "Sid": "UploadImages",
                "Effect": "Allow",
                "Action": [
                  "s3:PutObject",
                  "s3:GetObjectAcl",
                  "s3:GetObject",
                  "s3:PutObjectVersionAcl",
                  "s3:GetObjectVersionAcl",
                  "s3:ListBucket",
                  "s3:PutObjectTagging",
                  "s3:GetBucketLocation",
                  "s3:PutObjectAcl",
                  "s3:GetObjectVersion"
                ],
                "Resource": [
                  "arn:aws:s3:::$BUCKET/*",
                  "arn:aws:s3:::$BUCKET"
                ]
              },
              {
                "Sid": "InspectImagess",
                "Effect": "Allow",
                "Action": [
                  "s3:HeadBucket",
                  "s3:ListObjects"
                ],
                "Resource": "*"
              },
              {
                "Sid": "SelfCall",
                "Effect": "Allow",
                "Action": [
                  "lambda:InvokeFunction",
                  "lambda:InvokeAsync"
                ],
                "Resource": "$LAMBDA_ARN"
              }
            ]
        }
        ```
    
### Slack Setup (1)
I don't currently remember how I set up the slack side of things, so this is a combination of bad memory and guess-work. (I'd love pull requests to fix this.)
1. [Create a new app](https://api.slack.com/apps?new_app=1)
2. "OAuth & Permissions"
    1. Record the "Bot User OAuth Access Token"
    2. Add the following scopes:
        * channels:read 
        * chat:write:bot 
        * chat:write:user
        * bot
3. "Bot Users"
    1. Give it a display name and user name
    2. Mark as "Always show my bot as online"
4. Go to "Event Subscriptions"
    1. Enable it (top right)
    2. Set the Request URL to the API Endpoint from earlier
    2. Subscribe to Bot Events: message.channels
5. Go to "Basic Information" and record the "Verification Token"

### AWS Setup (2)
Return to the Lambda function you created previously and add the following environment variables:
* SLACK_TOKEN = $VerificationToken
* OAUTH_TOKEN = $BotUserOAuthAccessToken

### Slack Setup (2)
Return to Slack and under "Event Subscriptions" have it verify the endpoint

### Final
Figure out how to get it into your channels and it will respond to any LaTeX mathematic expression between two equals signs:
    $$\sum_{x=1}^{n} = \frac{n(n+1)}{2}$$
    

## Bugs
Due to cold-start problems this will multi-post when it hasn't been used in a while.
