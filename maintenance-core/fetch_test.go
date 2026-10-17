package maintenancecore
import("testing";"net/url";"encoding/json";"strings";"os")
const profileTest="vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?type=tcp&encryption=none&security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&fp=chrome&sni=example.com&sid=0123&spx=%2F"
func TestScope(t *testing.T){
 good:="https://iptv-org.github.io/iptv/languages/zho.m3u"
 if !CanFetch(good){t.Fatal("known configuration missing")}
 for _,bad:=range []string{good+"?video=1",good+"/video.ts","https://iptv-org.github.io/plugin.jar","http://iptv-org.github.io/iptv/languages/zho.m3u","https://user@iptv-org.github.io/iptv/languages/zho.m3u"}{
  if CanFetch(bad){t.Fatal("scope widened")};if _,e:=Fetch(profileTest,bad);e==nil{t.Fatal("unapproved fetch accepted")}
 }
}
func TestConfigIsolation(t *testing.T){
 b,e:=configuration(profileTest);if e!=nil{t.Fatal(e)};var c map[string]any;json.Unmarshal(b,&c)
 if _,ok:=c["inbounds"];ok{t.Fatal("listener configured")};if _,ok:=c["dns"];ok{t.Fatal("global dns configured")}
 if e=CheckProfile(profileTest);e!=nil{t.Fatal(e)}
 for _,bad:=range []string{"",strings.Replace(profileTest,"security=reality","security=none",1),strings.Replace(profileTest,"type=tcp","type=ws",1),strings.Replace(profileTest,"192.0.2.1","unknown.example",1)}{if CheckProfile(bad)==nil{t.Fatal("invalid profile accepted")}}
}
func TestRedirectScope(t *testing.T){o,_:=url.Parse("https://raw.githubusercontent.com/a/config.json")
 for _,target:=range []string{"http://raw.githubusercontent.com/a","https://example.com/a","https://raw.githubusercontent.com:444/a"}{v,_:=url.Parse(target);if redirectAllowed(o,v){t.Fatal("unsafe redirect")}}
}
func TestPrivateProfileStructure(t *testing.T){p:=os.Getenv("MAINTENANCE_PROFILE_TEST_FILE");if p==""{t.Skip("private profile absent")};b,e:=os.ReadFile(p);if e!=nil{t.Fatal("private input unavailable")};if CheckProfile(strings.TrimSpace(string(b)))!=nil{t.Fatal("private profile not supported")}}
