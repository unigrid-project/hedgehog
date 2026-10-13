/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.lambda.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateFunction {

	@JsonProperty(value = "Architectures")
	private String[] architectures;

	@Data
	private class Code {
		@JsonProperty(value = "ImageUri")
		private String imageUri;
		@JsonProperty(value = "S3Bucket")
		private String s3Bucket;
		@JsonProperty(value = "S3Key")
		private String s3Key;
		@JsonProperty(value = "S3ObjectVersion")
		private String s3ObjectVersion;
		@JsonProperty(value = "ZipFile")
		//TODO: find a blob class
		private byte[] zipFile;
	}

	@JsonProperty(value = "CodeSigningConfigArn")
	private String codeSigningConfigArn;

	@Data
	private class DeadLetterConfig {
		@JsonProperty(value = "TargetArn")
		private String targetArn;
	}

	@JsonProperty(value = "Description")
	private String description;

	@Data
	private class Environment {
		@JsonProperty(value = "Variables")
		Map<String, String> variables;
	}

	@Data
	private class EphemeralStorage {
		@JsonProperty(value = "Number")
		private int number;
	}

	@Data
	private class FileSystemConfigs {
		@JsonProperty(value = "Arn")
		private String arn;
		@JsonProperty(value = "LocalMountPath")
		private String localMountPath;
	}

	@JsonProperty(value = "FunctionName")
	private String functionName;
	@JsonProperty(value = "Handler")
	private String handler;

	@Data
	private class ImageConfig {
		@JsonProperty(value = "Command")
		private String[] command;
		@JsonProperty(value = "EntryPoint")
		private String[] entryPoint;
		@JsonProperty(value = "WorkingDirectory")
		private String workingDirectory;
	}

	@JsonProperty(value = "KMSKeyArn")
	private String kmsKeyArn;
	@JsonProperty(value = "Layers")
	private String[] layers;

	@Data
	private class LoggingConfig {
		@JsonProperty(value = "ApplicationLogLevel")
		private String applicationLogLevel;
		@JsonProperty(value = "LogFormat")
		private String logFormat;
		@JsonProperty(value = "LogGroup")
		private String logGroup;
		@JsonProperty(value = "SystemLogLevel")
		private String systemLogLevel;
	}

	@JsonProperty(value = "MemorySize")
	private int memorySize;
	@JsonProperty(value = "PackageType")
	private String packageType;
	@JsonProperty(value = "Publish")
	private boolean publish;
	@JsonProperty(value = "Role")
	private String role;
	@JsonProperty(value = "Runtime")
	private String runtime;

	@Data
	private class SnapStart {
		@JsonProperty(value = "ApplyOn")
		private String applyOn;
	}

	@JsonProperty(value = "Tags")
	private Map<String, String> tags;
	@JsonProperty(value = "Timeout")
	private int timeout;

	@Data
	private class TracingConfig {
		@JsonProperty(value = "Mode")
		private String mode;
	}

	@Data
	private class VpcConfig {
		@JsonProperty(value = "Ipv6AllowedForDaulStack")
		private boolean ipv6AllowedForDaulStack;
		@JsonProperty(value = "SecurityGroupsIds")
		private String[] securityGroupsIds;
		@JsonProperty(value = "SubnetIds")
		private String[] subnetIds;
	}
}
