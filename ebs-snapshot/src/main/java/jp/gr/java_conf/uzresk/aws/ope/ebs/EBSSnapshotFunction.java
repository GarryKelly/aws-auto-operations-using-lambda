package jp.gr.java_conf.uzresk.aws.ope.ebs;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Async;
import com.amazonaws.services.ec2.AmazonEC2AsyncClient;
import com.amazonaws.services.ec2.model.CreateSnapshotRequest;
import com.amazonaws.services.ec2.model.CreateSnapshotResult;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DeleteSnapshotRequest;
import com.amazonaws.services.ec2.model.DescribeSnapshotsRequest;
import com.amazonaws.services.ec2.model.DescribeSnapshotsResult;
import com.amazonaws.services.ec2.model.DescribeVolumesRequest;
import com.amazonaws.services.ec2.model.DescribeVolumesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Snapshot;
import com.amazonaws.services.ec2.model.SnapshotState;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.Volume;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.util.StringUtils;

import jp.gr.java_conf.uzresk.aws.lambda.LambdaLock;
import jp.gr.java_conf.uzresk.aws.ope.ebs.model.TagNameRequest;
import jp.gr.java_conf.uzresk.aws.ope.ebs.model.VolumeIdRequest;
import jp.gr.java_conf.uzresk.aws.ope.ebs.model.VolumeIdRequests;

public class EBSSnapshotFunction {

	private ClientConfiguration cc;

	public void createSnapshotFromVolumeId(VolumeIdRequest volumeIdRequest, Context context) {

		LambdaLogger logger = context.getLogger();
		logger.log("create ebs snapshot from volumeid Start. backup target[" + volumeIdRequest + "]");

		createSnapshot(volumeIdRequest.getVolumeId(), volumeIdRequest.getGenerationCount(), volumeIdRequest.getSnapshotName(), context);
	}

	public void createSnapshotFromVolumeIds(VolumeIdRequests volumeIdRequests, Context context) {

		LambdaLogger logger = context.getLogger();
		logger.log("create ebs snapshot from volumeids Start. backup target[" + volumeIdRequests + "]");

		for (VolumeIdRequest volumeIdRequest : volumeIdRequests.getVolumeIdRequests()) {
			createSnapshotFromVolumeId(volumeIdRequest, context);
		}
	}

	public void createSnapshotFromTagName(TagNameRequest tagNameRequest, Context context) {

		LambdaLogger logger = context.getLogger();
		logger.log("create ebs snapshot from tag name Start. backup target[" + tagNameRequest + "]");

		String regionName = System.getenv("AWS_DEFAULT_REGION");
		AmazonEC2Async client = RegionUtils.getRegion(regionName).createClient(AmazonEC2AsyncClient.class,
				new DefaultAWSCredentialsProviderChain(), cc);

		try {
			List<Volume> volumes = describeBackupVolumes(client, tagNameRequest);

			for (Volume volume : volumes) {
				createSnapshot(volume.getVolumeId(), tagNameRequest.getGenerationCount(), tagNameRequest.getSnapshotName(), context);
			}
		} finally {
			client.shutdown();
		}
	}

	List<Volume> describeBackupVolumes(AmazonEC2Async client, TagNameRequest target) {

		Filter tagKey = new Filter().withName("tag-key").withValues("Backup");
		Filter tagValue = new Filter().withName("tag-value").withValues(target.getTagName());
		DescribeVolumesRequest req = new DescribeVolumesRequest().withFilters(tagKey, tagValue);
		DescribeVolumesResult result = client.describeVolumes(req);

		return result.getVolumes();
	}

	void createSnapshot(String volumeId, int generationCount, final String snapshotName, Context context) {

		LambdaLogger logger = context.getLogger();

		boolean isLockAcquisition = new LambdaLock().lock(volumeId, context);
		if (!isLockAcquisition) {
			logger.log("[ERROR][EBSSnapshot][" + volumeId + "]You can not acquire a lock.");
			return;
		}

		String regionName = System.getenv("AWS_DEFAULT_REGION");
		AmazonEC2Async client = RegionUtils.getRegion(regionName).createClient(AmazonEC2AsyncClient.class,
				new DefaultAWSCredentialsProviderChain(), cc);

		try {
			// To get the snapshot
			String lambdaFunctionName = context.getFunctionName();
			String description = "Created by Lambda(" + lambdaFunctionName + ")";
			Future<CreateSnapshotResult> result = client
					.createSnapshotAsync(new CreateSnapshotRequest(volumeId, description));
			Snapshot snapshot = null;
			try {
				// waiting for snapshot
				while (!result.isDone()) {
					Thread.sleep(500);
				}
				snapshot = result.get().getSnapshot();
			} catch (InterruptedException | ExecutionException e) {
				throw new RuntimeException("create snapshot error. " + e.getMessage());
			}

			String snapshotId = snapshot.getSnapshotId();
			logger.log("EBS snapshot created. SnapshotId[" + snapshotId + "] VolumeId[" + volumeId + "]");

			// add a tag to the snapshot
			attachSnapshotTags(client, volumeId, snapshotId, snapshotName);

			pargeEbsSnapshot(client, volumeId, generationCount, context);

			logger.log("[SUCCESS][EBSSnapshot][" + volumeId + "] EBS Snapshot has completed successfully.");

		} catch (Exception e) {
			logger.log("[ERROR][EBSSnapshot][" + volumeId + "] message[" + e.getMessage() + "] stackTrace["
					+ getStackTrace(e) + "]");
		} finally {
			client.shutdown();
		}
	}

	void attachSnapshotTags(AmazonEC2Async client, String volumeId, String snapshotId, final String snapshotName) {

		List<Tag> tags = new ArrayList<Tag>();
		tags.add(new Tag("VolumeId", volumeId));
		tags.add(new Tag("BackupType", "snapshot"));
		if (!StringUtils.isNullOrEmpty(snapshotName))
			tags.add(new Tag("Name", snapshotName));

		CreateTagsRequest snapshotTagsRequest = new CreateTagsRequest().withResources(snapshotId);
		snapshotTagsRequest.setTags(tags);
		client.createTags(snapshotTagsRequest);
	}

	void pargeEbsSnapshot(AmazonEC2Async client, String volumeId, int generationCount, Context context) {

		LambdaLogger logger = context.getLogger();
		logger.log("Parge snapshot start. VolumeId[" + volumeId + "] generationCount[" + generationCount + "]");

		List<Filter> filters = new ArrayList<>();
		filters.add(new Filter().withName("volume-id").withValues(volumeId));
		filters.add(new Filter().withName("tag:VolumeId").withValues(volumeId));
		filters.add(new Filter().withName("tag:BackupType").withValues("snapshot"));
		DescribeSnapshotsRequest snapshotRequest = new DescribeSnapshotsRequest().withFilters(filters);
		DescribeSnapshotsResult snapshotResult = client.describeSnapshots(snapshotRequest);

		List<Snapshot> snapshots = snapshotResult.getSnapshots();
		Collections.sort(snapshots, new SnapshotComparator());

		int snapshotSize = snapshots.size();
		if (generationCount < snapshotSize) {
			for (int i = 0; i < snapshotSize - generationCount; i++) {
				Snapshot snapshot = snapshots.get(i);
				if (SnapshotState.Completed.toString().equals(snapshot.getState())) {
					String snapshotId = snapshot.getSnapshotId();
					pargeSnapshot(client, snapshotId);
					logger.log("Parge EBS snapshot. snapshotId[" + snapshotId + "] volumeId[" + volumeId + "]");
				}
			}
		}
	}

	private class SnapshotComparator implements Comparator<Snapshot> {
		@Override
		public int compare(Snapshot o1, Snapshot o2) {
			Date startDateO1 = o1.getStartTime();
			Date startDateO2 = o2.getStartTime();
			return startDateO1.compareTo(startDateO2);
		}
	}

	void pargeSnapshot(AmazonEC2 ec2, String snapshotId) {
		DeleteSnapshotRequest request = new DeleteSnapshotRequest(snapshotId);
		ec2.deleteSnapshot(request);
	}

	String getStackTrace(Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		pw.flush();
		return sw.toString();
	}

	void setClientConfiguration(ClientConfiguration cc) {
		this.cc = cc;
	}

	ClientConfiguration getClientConfiguration() {
		if (this.cc == null) {
			return new ClientConfiguration();
		}
		return this.cc;
	}

}
