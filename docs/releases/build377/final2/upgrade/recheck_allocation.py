from power import *
rec=get_record();assert rec['status']=='BASELINE_SUSPENDED'
prev=rec['shutdowns'][-1]['privateAvdStorage']
rec['measurementCorrection']={'reason':'Host allocation counts use FILE_STANDARD_INFO.AllocationSize, matching the formal release. The previous GetCompressedFileSizeW count can equal EOF for ordinary files. Same retained files re-read after guest shutdown.', 'at':now(),'initialCompressedSizeApiValues':{'apk':rec['stages']['baseline']['input']['allocatedBytes'],'avdTotal':prev['allocatedBytes']}}
apk=Path(rec['stages']['baseline']['input']['path']);assert digest(apk)==rec['baselineExpected']
rec['stages']['baseline']['input']['allocatedBytes']=allocated(apk)
current=disk_usage();assert prev['fileCount']==current['fileCount'] and prev['logicalBytes']==current['logicalBytes']
prev.update(allocatedBytes=current['allocatedBytes'],allocationRemeasuredAt=now(),remeasureVolumeFreeBytes=current['sameVolumeFreeBytes'])
rec['earlierAttempt']={'path':'baseline-r1-report.json','result':'First AVD identity parser counted a blank console line before OK; exact AVD name matched after ignoring blank lines. No package installed on that attempt.'}
put('direct.json',rec)
print('APK allocated',allocated(apk),'AVD',current)
