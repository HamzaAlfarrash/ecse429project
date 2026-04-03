#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
THINGIFIER_TAG="${THINGIFIER_TAG:-1.5.5}"
SOURCE_DIR="${THINGIFIER_SOURCE_DIR:-"$ROOT_DIR/.external/thingifier-$THINGIFIER_TAG"}"
SONAR_CONTAINER_NAME="${SONAR_CONTAINER_NAME:-partc-sonarqube}"
SONAR_IMAGE="${SONAR_IMAGE:-sonarqube:9.9-community}"
SCANNER_IMAGE="${SCANNER_IMAGE:-sonarsource/sonar-scanner-cli:5}"
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9-eclipse-temurin-8}"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"
PROJECT_KEY="${PROJECT_KEY:-thingifier-1.5.5}"
PROJECT_NAME="${PROJECT_NAME:-thingifier-1.5.5}"
PROJECT_VERSION="${PROJECT_VERSION:-1.5.5}"
ARTIFACT_DIR="$ROOT_DIR/part_c/static-analysis/artifacts"
SONAR_STATE_DIR="$ROOT_DIR/.external/sonarqube"
M2_DIR="${HOME}/.m2"

"$ROOT_DIR/scripts/setup_thingifier_source.sh" "$SOURCE_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required to run the SonarQube analysis workflow." >&2
  exit 1
fi

mkdir -p "$ARTIFACT_DIR" "$SONAR_STATE_DIR/data" "$SONAR_STATE_DIR/logs" "$SONAR_STATE_DIR/extensions" "$M2_DIR"

if ! docker ps --format '{{.Names}}' | grep -Fxq "$SONAR_CONTAINER_NAME"; then
  if docker ps -a --format '{{.Names}}' | grep -Fxq "$SONAR_CONTAINER_NAME"; then
    echo "Starting existing SonarQube container: $SONAR_CONTAINER_NAME"
    docker start "$SONAR_CONTAINER_NAME" >/dev/null
  else
    echo "Creating SonarQube 9.9 LTS container: $SONAR_CONTAINER_NAME"
    docker run -d \
      --name "$SONAR_CONTAINER_NAME" \
      -p 9000:9000 \
      -v "$SONAR_STATE_DIR/data:/opt/sonarqube/data" \
      -v "$SONAR_STATE_DIR/logs:/opt/sonarqube/logs" \
      -v "$SONAR_STATE_DIR/extensions:/opt/sonarqube/extensions" \
      "$SONAR_IMAGE" >/dev/null
  fi
fi

echo "Waiting for SonarQube to report status UP..."
until curl -fsS "$SONAR_HOST_URL/api/system/status" | grep -q '"status":"UP"'; do
  sleep 5
done

echo "Compiling Thingifier source with Maven for Java analysis..."
docker run --rm \
  -v "$SOURCE_DIR:/usr/src/app" \
  -v "$M2_DIR:/root/.m2" \
  -w /usr/src/app \
  "$MAVEN_IMAGE" \
  mvn -DskipTests compile

BINARY_PATHS="$(cd "$SOURCE_DIR" && find . -type d -path '*/target/classes' | paste -sd, -)"
if [[ -z "$BINARY_PATHS" ]]; then
  echo "No compiled Java class directories were found under $SOURCE_DIR" >&2
  exit 1
fi

echo "Running SonarQube scan for $PROJECT_KEY..."
docker run --rm \
  --network "container:$SONAR_CONTAINER_NAME" \
  -v "$SOURCE_DIR:/usr/src" \
  -w /usr/src \
  --entrypoint /bin/sh \
  "$SCANNER_IMAGE" \
  -lc "sonar-scanner \
    -Dsonar.projectKey='$PROJECT_KEY' \
    -Dsonar.projectName='$PROJECT_NAME' \
    -Dsonar.projectVersion='$PROJECT_VERSION' \
    -Dsonar.sources=. \
    -Dsonar.java.binaries='$BINARY_PATHS' \
    -Dsonar.host.url=http://127.0.0.1:9000 \
    -Dsonar.login=admin \
    -Dsonar.password=admin"

echo "Exporting SonarQube findings to $ARTIFACT_DIR"
curl -fsS -u admin:admin \
  "$SONAR_HOST_URL/api/issues/search?componentKeys=$PROJECT_KEY&ps=500" \
  > "$ARTIFACT_DIR/issues-search.json"

curl -fsS -u admin:admin \
  "$SONAR_HOST_URL/api/measures/component?component=$PROJECT_KEY&metricKeys=bugs,vulnerabilities,code_smells,ncloc,complexity,cognitive_complexity,duplicated_lines_density,sqale_index,reliability_rating,security_rating" \
  > "$ARTIFACT_DIR/project-measures.json"

cat <<EOF
SonarQube analysis completed.

Open the dashboard at:
  $SONAR_HOST_URL/dashboard?id=$PROJECT_KEY

Exported artifacts:
  $ARTIFACT_DIR/issues-search.json
  $ARTIFACT_DIR/project-measures.json
EOF
