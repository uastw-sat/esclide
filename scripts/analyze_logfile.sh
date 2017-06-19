#!/bin/bash

# This script is used to analyze log files either at runtime within the cloud container 
# or locally later on specifying, a specific logfile or a folder with multiple logfiles. 
# author:    Jürgen Hausladen
# copyright: 2017, SAT, UAS Technikum Wien
# License:   AGPL <http://www.gnu.org/licenses/agpl.txt>

# Function to print information on script usage
usage() { echo "Usage: $0 [-n <last-X-entries> -c <container> -s <select-container-from-configurationfile> -f <logfile> -l <local-logfile> -o <legacy-logfile-support>"] 1>&2; exit 1; }

# Function to read a cloud configuration file and parse the name of each docker container
readConfigFile() {
  while IFS= read -r line
    do
      if [[ "$line" == "name ="* ]] ; then
        container_name="${line/name = /}"
        if [ "$CONTAINERLIST" == "" ] ; then CONTAINERLIST=$container_name
        else CONTAINERLIST="$CONTAINERLIST $container_name"
        fi
      fi
  done <"$1"
}

# Check number of arguments
#if [ $# == 0 ] ; then usage ; exit 1 ; fi

FILEPATH="/var/log/supervisor/cloud9.log"
LOCALLOGFILEPATH=""
CONTAINER=""
CONTAINERLIST=""
LOGPATTERN="tty.*Client"
NUMBEROFENTRIES=-2

# Iterate over commandline arguments
while getopts ':n:c:s:f:l:oh' OPTION ; do
  case "$OPTION" in
    n)   if [[ $OPTARG =~ ^[0-9]+$ ]] ; then NUMBEROFENTRIES=$(($OPTARG*-1)); else echo "Error (-n): Not a number!" 1>&2; exit 1;fi;;
    c)   CONTAINER=$OPTARG;;
    s)   readConfigFile $OPTARG;;
    f)   FILEPATH=$OPTARG;;
    l)   LOCALLOGFILEPATH=$OPTARG;;
    o)   LOGPATTERN="User.*!";;
    h)   usage ;;
    *)   echo "Unknown parameter -$OPTARG !" && usage 
  esac
done

# Iterate through all containers, output and analyze the logfile
if [ "$CONTAINER" == "" ] && [ "$CONTAINERLIST" == "" ] && [ "$LOCALLOGFILEPATH" == "" ] ; then
  for container in $(docker ps --format '{{.Names}}'); do
    echo "Processing: $container"
    docker exec $container grep $LOGPATTERN $FILEPATH | sed s/.*\]// | tail $NUMBEROFENTRIES
  done
else
  #  Analyze the logfile of a aspecific container
  if [ "$CONTAINER" != "" ] ; then
    echo "Processing: $CONTAINER"
    docker exec $CONTAINER grep $LOGPATTERN $FILEPATH | sed s/.*\]// | tail $NUMBEROFENTRIES
  # Analyze the logfile of all containers specified in the cloud configuration file
  elif [ "$CONTAINERLIST" != "" ] ; then
    for container in $CONTAINERLIST ; do
      echo "Processing: $container"
      docker exec $container grep $LOGPATTERN $FILEPATH | sed s/.*\]// | tail $NUMBEROFENTRIES
    done
  # Analyze locally stored logfiles
  else
    # Check if the path to the logfile is a folder
    if [[ -d $LOCALLOGFILEPATH ]]; then
      for configfile in *.log; do
        echo "Processing: $configfile"
        grep $LOGPATTERN $configfile | sed s/.*\]// | tail $NUMBEROFENTRIES
      done
    # Check if the path to the logfile is a file
    elif [[ -f $LOCALLOGFILEPATH ]]; then
      echo "Processing: $LOCALLOGFILEPATH"
      grep $LOGPATTERN $LOCALLOGFILEPATH | sed s/.*\]// | tail $NUMBEROFENTRIES
    else
      echo "$LOCALLOGFILEPATH is not valid!"
      exit 1
    fi
  fi
fi