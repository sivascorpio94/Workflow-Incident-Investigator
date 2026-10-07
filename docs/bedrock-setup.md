# Giving this project its own Bedrock space

Goal: keep this project's Bedrock usage separate from whatever your local AWS/Bedrock setup already does:
separate credentials, least-privilege permissions, and separately tracked cost. Run these on your machine
(they need your AWS credentials; nothing here is executed by the repo).

Replace `<ACCOUNT_ID>`, `<REGION>` (plan uses `us-east-2`) and `<MODEL_ID>` (a chat model enabled in your account).

## 1. Dedicated named profile (separate credentials)

```bash
aws configure --profile incident-assistant        # or `aws configure sso --profile incident-assistant`
export AWS_PROFILE=incident-assistant AWS_REGION=<REGION>
aws sts get-caller-identity                       # confirm it is the identity you intend
```

Both implementations use the standard AWS credential chain, so `AWS_PROFILE` is all they need.
Credentials stay in `~/.aws`, never in the repo.

## 2. Least-privilege IAM policy

Attach `docs/aws/incident-assistant-bedrock-policy.json` to the user/role behind that profile. It allows only model
invocation (Converse uses `bedrock:InvokeModel`); it cannot list, create or change anything. Edit the Resource ARNs
to your region, model and (if used) inference profile.

## 3. Model access

In the Bedrock console (region `<REGION>`) enable access to the model you chose. Then:

```bash
aws bedrock list-foundation-models --region <REGION> --query "modelSummaries[].modelId"
aws bedrock list-inference-profiles --region <REGION>     # newer models often require an inference profile id (us.*)
```

## 4. Optional: application inference profile (separate cost tracking)

An application inference profile is a named, taggable handle on a model, so its usage shows up separately in
Cost Explorer. Create it from a system inference profile or foundation model ARN:

```bash
aws bedrock create-inference-profile --region <REGION> \
  --inference-profile-name incident-assistant \
  --model-source copyFrom=<SYSTEM_PROFILE_OR_MODEL_ARN> \
  --tags key=project,value=incident-assistant
```

Use the returned `inferenceProfileArn` as `BEDROCK_MODEL_ID`. Activate the `project` tag as a cost-allocation tag in
Billing. Add a budget alert; a full 4-scenario eval is only ~16 model calls, but alarms are cheap.

## 5. Keys in a `.env` file

Copy `.env.example` to `.env` (git-ignored) and fill it in. Python loads it automatically; for Java load it into the
shell first, because the AWS SDK reads real environment variables, not Spring properties:

```bash
set -a; source .env; set +a
```

Prefer a profile or a Bedrock API key (`AWS_BEARER_TOKEN_BEDROCK`) over long-lived access keys, and never paste a key
into chat, code, or a commit.

## 6. Run

```bash
export AWS_PROFILE=incident-assistant AWS_REGION=<REGION> BEDROCK_MODEL_ID=<model id or inference profile ARN>
cd java && mvn spring-boot:run          # or: python -m incident_graph eval   (from python/)
```

If you get `AccessDeniedException`, the policy resources don't match the model/profile/region you are invoking;
if `ValidationException ... on-demand throughput isn't supported`, use an inference profile id instead of the bare model id.
