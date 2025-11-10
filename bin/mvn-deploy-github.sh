#!/usr/bin/env bash
set -Eeuo pipefail

MVN_SETTINGS_TEMPLATE_FILE=.mvn/settings.xml.tmpl
MVN_SETTINGS_FILE=.mvn/settings.xml
POM_FILE=pom.xml

sed -e "s|GH_REPO|${GH_REPO:n.a.}|g" \
    -e "s|GH_DEPLOY_USERNAME|${GH_DEPLOY_USERNAME:-}|g" \
    -e "s|GH_DEPLOY_TOKEN|${GH_DEPLOY_TOKEN:-}|g" \
    -e "s|CENTRAL_DEPLOY_USERNAME|${CENTRAL_DEPLOY_USERNAME:-}|g" \
    -e "s|CENTRAL_DEPLOY_TOKEN|${CENTRAL_DEPLOY_TOKEN:-}|g" \
    "$MVN_SETTINGS_TEMPLATE_FILE" > "$MVN_SETTINGS_FILE"

if [[ $GITHUB_REF_NAME == v* ]]; then
  echo "creating release version ${GITHUB_REF_NAME#v}"
  mvn -s ${MVN_SETTINGS_FILE} --no-transfer-progress --batch-mode release:prepare -DpushChanges=false -DreleaseVersion=${GITHUB_REF_NAME#v} || true
fi

mvn -s ${MVN_SETTINGS_FILE} --no-transfer-progress --batch-mode deploy -Dlicense.skip=true
