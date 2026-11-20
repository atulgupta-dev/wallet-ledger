#!/bin/sh
# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script, version 3.3.2
# ----------------------------------------------------------------------------

set -e

# OS specific support.
cygwin=false;
darwin=false;
mingw=false
case "`uname`" in
  CYGWIN*) cygwin=true ;;
  Darwin*) darwin=true ;;
  MINGW*) mingw=true ;;
esac

## Resolve links
PRG="$0"
while [ -h "$PRG" ] ; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done

saveddir=`pwd`
MAVEN_PROJECTBASEDIR=`dirname "$PRG"`
cd "$MAVEN_PROJECTBASEDIR"
MAVEN_PROJECTBASEDIR=`pwd`
cd "$saveddir"

export MAVEN_PROJECTBASEDIR

# Need to find wrapper jar or download maven
WRAPPER_DIR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper"
WRAPPER_PROPERTIES="$WRAPPER_DIR/maven-wrapper.properties"

if [ -f "$WRAPPER_PROPERTIES" ]; then
    DIST_URL=$(grep '^distributionUrl=' "$WRAPPER_PROPERTIES" | cut -d'=' -f2)
fi

# Fallback distribution
if [ -z "$DIST_URL" ]; then
    DIST_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
fi

MAVEN_HOME="$HOME/.m2/wrapper/dists/$(echo "$DIST_URL" | sed 's/[^a-zA-Z0-9]/_/g')"

if [ ! -d "$MAVEN_HOME" ]; then
    mkdir -p "$MAVEN_HOME"
    echo "Downloading Apache Maven..."
    ZIP_FILE="$MAVEN_HOME/maven-bin.zip"
    if command -v curl >/dev/null 2>&1; then
        curl -sSL "$DIST_URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$DIST_URL" -O "$ZIP_FILE"
    else
        echo "Error: curl or wget is required to download Maven wrapper." >&2
        exit 1
    fi
    unzip -q -o "$ZIP_FILE" -d "$MAVEN_HOME"
    rm -f "$ZIP_FILE"
fi

MVN_EXEC=$(find "$MAVEN_HOME" -type f -name "mvn" -perm -111 2>/dev/null | head -n 1)
if [ -z "$MVN_EXEC" ]; then
    MVN_EXEC=$(find "$MAVEN_HOME" -type f -name "mvn" 2>/dev/null | head -n 1)
    chmod +x "$MVN_EXEC" 2>/dev/null || true
fi

exec "$MVN_EXEC" "$@"
