from pathlib import Path
import zipfile,io,hashlib,re,json
import xml.etree.ElementTree as ET

p=Path('work/sdk-project');host=p/'examples/binary_host'
for file in ['work/sdk-final-build.log','work/binary-host-build.log']:
    assert 'BUILD SUCCESSFUL' in Path(file).read_text(encoding='utf-8')
results=[]
for module,folder in [('integration','test'),('model_client','testDebugUnitTest'),('android_library','testDebugUnitTest'),('assistant_feature','testDebugUnitTest')]:
    for file in (p/module/'build/test-results'/folder).glob('TEST-*.xml'):
        suite=ET.parse(file).getroot();assert int(suite.attrib['failures'])==int(suite.attrib['errors'])==0
        results.append({'suite':suite.attrib['name'],'tests':int(suite.attrib['tests']),'failures':0})
assert sum(r['tests'] for r in results)==46
manifest=host/'app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml'
root=ET.parse(manifest).getroot();a='{http://schemas.android.com/apk/res/android}'
assert root.attrib['package']=='org.example.assistanthost'
permissions={node.attrib[a+'name'] for node in root.findall('uses-permission')}
expected={'INTERNET','SYSTEM_ALERT_WINDOW','FOREGROUND_SERVICE','FOREGROUND_SERVICE_SPECIAL_USE','FOREGROUND_SERVICE_MEDIA_PROJECTION','POST_NOTIFICATIONS'}
assert all('android.permission.'+s in permissions for s in expected)
app=root.find('application');services={s.attrib[a+'name']:s for s in app.findall('service')}
access=services['com.bagent.assistant.AssistantAccessService']
assert access.attrib[a+'permission']=='android.permission.BIND_ACCESSIBILITY_SERVICE'
assert access.attrib[a+'exported']=='true' and set(access.attrib[a+'foregroundServiceType'].split('|'))=={'specialUse','mediaProjection'}
floating=services['com.bagent.assistant.FloatingService'];assert floating.attrib[a+'exported']=='false'
assert any(m.attrib.get(a+'resource')=='@xml/bagent_accessibility_service' for m in access.findall('meta-data'))
activities={x.attrib[a+'name']:x for x in app.findall('activity')}
for name in ['AssistantSetupActivity','ConfirmDialogActivity','ErrorDialogActivity']:
    assert activities['com.bagent.assistant.'+name].attrib[a+'exported']=='false'
assert activities['com.bagent.assistant.ConfirmDialogActivity'].attrib[a+'taskAffinity']=='org.example.assistanthost.bagent.dialog'
assert sum(1 for act in app.findall('activity') for intent in act.findall('intent-filter') for action in intent.findall('action') if action.attrib[a+'name']=='android.intent.action.MAIN')==1
original=Path('work/project/sample_app/src/main/java/com/bagent/sample/SampleAccessService.kt').read_text(encoding='utf-8')
secret=re.search(r'const val API_KEY = "([^"]+)"',original).group(1).encode()
aar_paths=list((p/'dist/maven').rglob('*.aar'));assert len(aar_paths)==3
for file in aar_paths:
    with zipfile.ZipFile(file) as z:
        for name in z.namelist():
            data=z.read(name);assert secret not in data
            if name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(data)) as j:
                    for entry in j.namelist():assert secret not in j.read(entry),(file,entry)
for jar in (p/'dist/maven').rglob('*sources.jar'):
    with zipfile.ZipFile(jar) as z:
        assert all(secret not in z.read(n) for n in z.namelist())
release=host/'app/build/outputs/apk/release/app-release.apk'
assert release.exists() and (host/'app/build/outputs/mapping/release/mapping.txt').exists()
with zipfile.ZipFile(release) as z: assert all(secret not in z.read(n) for n in z.namelist())
lints={}
for label,file in [('sdk',p/'sample_app/build/reports/lint-results-debug.xml'),('binary_host',host/'app/build/reports/lint-results-debug.xml')]:
    issues=ET.parse(file).getroot().findall('issue')
    assert all(i.attrib['severity'] not in ('Error','Fatal') for i in issues)
    lints[label]={'errors':0,'warnings':sum(i.attrib['severity']=='Warning' for i in issues)}
report={'sdk_version':'1.3.0','algorithm_version':'1.3','unit_tests':results,'unit_test_total':46,
        'maven_binary_host_application_id':'org.example.assistanthost','release_minify_build':'passed',
        'manifest_permissions_and_components':'passed','SDK_AAR_and_sources_no_embedded_demo_key':'passed',
        'binary_host_no_embedded_demo_key':'passed','lint':lints,'device_tests':'compiled, not run','final_user_host_app':'not provided; not tested'}
Path('work/sdk-artifact-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
