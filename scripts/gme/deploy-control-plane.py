"""Package inspection by default; --apply explicitly uploads and deploys the new stack."""
import argparse
import hashlib
import json
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="MotoCom GME CloudFormation deployment")
    parser.add_argument("--profile", default="agent-toolkit")
    parser.add_argument("--region", default="ap-southeast-2")
    parser.add_argument("--stack", default="motocom-gme")
    parser.add_argument("--bucket", required=True, help="Existing private artifact bucket in this region")
    parser.add_argument("--secret-name", default="motocom/gme/app-key")
    parser.add_argument("--alarm-email", default="", help="Optional SNS email subscription target")
    parser.add_argument("--validate-only", action="store_true", help="Run the read-only AWS template validator")
    parser.add_argument("--apply", action="store_true", help="Upload artifact, create and execute a CloudFormation change set")
    args = parser.parse_args()
    if args.apply and args.validate_only:
        parser.error("--apply and --validate-only are mutually exclusive")
    if args.stack == "motocom-gme-ticket":
        parser.error("The legacy experiment stack is not a deployment target")
    root = Path(__file__).resolve().parents[2]
    template = (root / "infrastructure/gme/template.yaml").read_text(encoding="utf-8")
    artifact = root / "backend/gme-control-plane/.build/control-plane.zip"
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    key = f"{args.stack}/{digest}.zip"
    print(json.dumps({"stack": args.stack, "region": args.region, "artifactKey": key,
                      "bytes": artifact.stat().st_size, "apply": args.apply}, ensure_ascii=False))
    if not (args.apply or args.validate_only):
        return
    import boto3
    session = boto3.Session(profile_name=args.profile, region_name=args.region)
    account = session.client("sts").get_caller_identity()["Account"]
    if account != "401921623615":
        raise RuntimeError("Unexpected AWS account; no deployment performed")
    cf = session.client("cloudformation")
    cf.validate_template(TemplateBody=template)
    print("CloudFormation ValidateTemplate: PASS")
    if args.validate_only:
        return
    s3 = session.client("s3")
    config = s3.get_public_access_block(Bucket=args.bucket)["PublicAccessBlockConfiguration"]
    if not all(config.get(k) for k in ["BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets"]):
        raise RuntimeError("Artifact bucket must block public access")
    location = s3.get_bucket_location(Bucket=args.bucket).get("LocationConstraint") or "us-east-1"
    if location != args.region:
        raise RuntimeError("Artifact bucket must be in the deployment region")
    s3.upload_file(str(artifact), args.bucket, key, ExtraArgs={"ServerSideEncryption": "AES256"})
    from botocore.exceptions import ClientError
    try:
        cf.describe_stacks(StackName=args.stack)
        change_type = "UPDATE"
    except ClientError as error:
        if error.response["Error"]["Code"] != "ValidationError":
            raise
        change_type = "CREATE"
    changeset = f"gme-{digest[:16]}-{time.time_ns()}"
    response = cf.create_change_set(
        StackName=args.stack, ChangeSetName=changeset, ChangeSetType=change_type,
        Description="Signed-device GME control plane; no media storage",
        TemplateBody=template, Capabilities=["CAPABILITY_IAM"],
        Parameters=[{"ParameterKey": k, "ParameterValue": v} for k, v in {
            "ArtifactBucket": args.bucket, "ArtifactKey": key, "GmeAppKeySecretName": args.secret_name,
            "AlarmEmail": args.alarm_email,
        }.items()], Tags=[{"Key": "project", "Value": "motocom-gme"}],
    )
    cf.get_waiter("change_set_create_complete").wait(StackName=args.stack, ChangeSetName=response["Id"])
    plan = cf.describe_change_set(StackName=args.stack, ChangeSetName=response["Id"])
    print(json.dumps([{k: c["ResourceChange"].get(k) for k in ["Action", "LogicalResourceId", "ResourceType", "Replacement"]}
                      for c in plan.get("Changes", [])], ensure_ascii=False, indent=2))
    cf.execute_change_set(StackName=args.stack, ChangeSetName=response["Id"])
    cf.get_waiter("stack_create_complete" if change_type == "CREATE" else "stack_update_complete").wait(StackName=args.stack)
    print("CloudFormation deployment: COMPLETE")


if __name__ == "__main__":
    main()
