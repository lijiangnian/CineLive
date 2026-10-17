package maintenancecore

import (
 "context"
 "errors"
 "io"
 "net"
 "net/http"
 "net/url"
 "os"
 "regexp"
 "strconv"
 "strings"
 "time"
 xnet "github.com/xtls/xray-core/common/net"
 "github.com/xtls/xray-core/core"
)

// A separate capability: only this application's release downloads, never general GitHub traffic.
const releaseRoot = "/lijiangnian/CineLive/releases/"
var releasePath = regexp.MustCompile(`^/lijiangnian/CineLive/releases/(latest/download/manifest\.json|download/[A-Za-z0-9._-]+/(manifest\.json|CineLive-[A-Za-z0-9._-]+\.apk))$`)
func CanFetchUpdate(address string) bool {
 u,e:=url.Parse(address)
 return e==nil && u.Scheme=="https" && u.Host=="github.com" && u.User==nil && u.RawQuery=="" && u.Fragment=="" && u.RawPath=="" && !strings.Contains(u.Path,"/../") && !strings.Contains(u.Path,"/./") && releasePath.MatchString(u.Path)
}
func updateRedirectAllowed(current *url.URL) bool {
 if current.Scheme!="https" || current.User!=nil || current.Fragment!="" {return false}
 if current.Host=="github.com" {return CanFetchUpdate(current.String())}
 // GitHub signed asset links are allowed only as a redirect from the approved release.
 return current.Host=="release-assets.githubusercontent.com" && strings.HasPrefix(current.Path,"/github-production-release-asset/")
}
// UpdateDownload streams to a private temporary file; no APK-sized Java/Go memory buffer.
type UpdateDownload struct { profile,address,path string; limit int64; ctx context.Context; cancel context.CancelFunc }
func NewUpdateDownload(profile,address,path string,limit int64) (*UpdateDownload,error) {
 if !CanFetchUpdate(address) || limit<=0 || limit>512*1024*1024 || path=="" {return nil,errors.New("update outside approved scope")}
 if strings.HasSuffix(address,"manifest.json") && limit>256*1024 {return nil,errors.New("manifest limit exceeded")}
 ctx,cancel:=context.WithTimeout(context.Background(),180*time.Second)
 return &UpdateDownload{profile,address,path,limit,ctx,cancel},nil
}
func (d *UpdateDownload) Cancel() {d.cancel()}
func (d *UpdateDownload) Run() error {
 defer d.cancel()
 if d.ctx.Err()!=nil{return errors.New("update cancelled")}
 b,e:=configuration(d.profile);if e!=nil{return e}
 instance,e:=core.StartInstance("json",b);if e!=nil{return errors.New("update route unavailable")};defer instance.Close()
 transport:=&http.Transport{Proxy:nil,DisableKeepAlives:true,MaxResponseHeaderBytes:32*1024,TLSHandshakeTimeout:12*time.Second,
 DialContext:func(ctx context.Context,network,address string)(net.Conn,error){
  host,p,e:=net.SplitHostPort(address);if e!=nil{return nil,e};port,e:=strconv.Atoi(p)
  if e!=nil||port!=443||(host!="github.com"&&host!="release-assets.githubusercontent.com"){return nil,errors.New("update destination rejected")}
  return core.Dial(ctx,instance,xnet.TCPDestination(xnet.ParseAddress(host),xnet.Port(port)))
 }}
 defer transport.CloseIdleConnections()
 client:=&http.Client{Transport:transport,Timeout:180*time.Second,CheckRedirect:func(req *http.Request,via []*http.Request)error{
  if len(via)>=4||!updateRedirectAllowed(req.URL){return errors.New("update redirect rejected")};return nil
 }}
 req,e:=http.NewRequestWithContext(d.ctx,http.MethodGet,d.address,nil);if e!=nil{return errors.New("invalid update URL")}
 req.Header.Set("User-Agent","CineLive-Updater/1")
 response,e:=client.Do(req);if e!=nil{return errors.New("update download unavailable")};defer response.Body.Close()
 if response.StatusCode!=200||response.ContentLength>d.limit{return errors.New("invalid update response")}
 out,e:=os.OpenFile(d.path,os.O_WRONLY|os.O_CREATE|os.O_TRUNC,0600);if e!=nil{return errors.New("cannot save update")}
 n,e:=io.Copy(out,io.LimitReader(response.Body,d.limit+1));closeErr:=out.Close()
 if e!=nil||closeErr!=nil||n<=0||n>d.limit||d.ctx.Err()!=nil{os.Remove(d.path);return errors.New("incomplete update")}
 return nil
}
