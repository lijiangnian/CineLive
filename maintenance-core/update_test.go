package maintenancecore
import("testing";"net/url";"path/filepath";"os")
func TestUpdateScope(t *testing.T){
 good:=[]string{"https://github.com/lijiangnian/CineLive/releases/latest/download/manifest.json","https://github.com/lijiangnian/CineLive/releases/download/v0.1.8/CineLive-tv-arm32-0.1.8.apk"}
 for _,s:=range good{if !CanFetchUpdate(s){t.Fatal("rejected release")};if CanFetch(s){t.Fatal("expanded subscription scope")}}
 bad:=[]string{"http://github.com/lijiangnian/CineLive/releases/latest/download/manifest.json","https://github.com/other/CineLive/releases/latest/download/manifest.json","https://github.com/lijiangnian/CineLive/releases/download/v1/plugin.jar","https://github.com/lijiangnian/CineLive/releases/latest/download/manifest.json?next=x","https://github.com/lijiangnian/CineLive/releases/download/../CineLive-x.apk","https://release-assets.githubusercontent.com/github-production-release-asset/x"}
 for _,s:=range bad{if CanFetchUpdate(s){t.Fatal("accepted unrelated URL")}}
}
func TestUpdateRedirectScope(t *testing.T){
 for _,s:=range []string{"https://github.com/login","https://example.com/update.apk","http://release-assets.githubusercontent.com/github-production-release-asset/a","https://release-assets.githubusercontent.com/anything"}{u,_:=url.Parse(s);if updateRedirectAllowed(u){t.Fatal("redirect widened route")}}
 u,_:=url.Parse("https://release-assets.githubusercontent.com/github-production-release-asset/123/file?signature=x");if !updateRedirectAllowed(u){t.Fatal("signed release redirect rejected")}
}
func TestUpdateCancelBeforeNetwork(t *testing.T){
 path:=filepath.Join(t.TempDir(),"update.part")
 d,e:=NewUpdateDownload("", "https://github.com/lijiangnian/CineLive/releases/latest/download/manifest.json",path,256*1024);if e!=nil{t.Fatal(e)}
 d.Cancel();if d.Run()==nil{t.Fatal("cancel ignored")};if _,e=os.Stat(path);!os.IsNotExist(e){t.Fatal("cancelled file created")}
}
