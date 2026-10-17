// Package maintenancecore exposes a bounded document fetcher, never a VPN or general proxy listener.
package maintenancecore

import (
 "context"
 "encoding/base64"
 "encoding/hex"
 "encoding/json"
 "errors"
 "io"
 "net"
 "net/http"
 "net/url"
 "strconv"
 "strings"
 "time"

 xnet "github.com/xtls/xray-core/common/net"
 "github.com/xtls/xray-core/core"
 _ "github.com/xtls/xray-core/app/dispatcher"
 _ "github.com/xtls/xray-core/app/log"
 _ "github.com/xtls/xray-core/app/proxyman/outbound"
 _ "github.com/xtls/xray-core/app/proxyman/inbound"
 _ "github.com/xtls/xray-core/main/json"
 _ "github.com/xtls/xray-core/proxy/vless/outbound"
 _ "github.com/xtls/xray-core/transport/internet/tcp"
 _ "github.com/xtls/xray-core/transport/internet/reality"
)

const maxBytes=4*1024*1024
// Result never contains the node address or authorization data.
type Result struct { Body []byte; URL string; Status int; RetryAfter string }
func CanFetch(address string) bool { return allowed[address] }

func configuration(profile string) ([]byte,error) {
 invalid:=errors.New("invalid private maintenance profile")
 u,err:=url.Parse(profile);if err!=nil || u.Scheme!="vless" || u.User==nil || net.ParseIP(u.Hostname())==nil {return nil,invalid}
 if _,has:=u.User.Password();has{return nil,invalid}
 port,err:=strconv.Atoi(u.Port());if err!=nil||port<1||port>65535{return nil,invalid}
 id:=u.User.Username();raw,err:=hex.DecodeString(strings.ReplaceAll(id,"-",""));if err!=nil||len(raw)!=16{return nil,invalid}
 q:=u.Query();if q.Get("security")!="reality" || q.Get("type")!="tcp" || q.Get("encryption")!="none" {return nil,invalid}
 key,err:=base64.RawURLEncoding.DecodeString(q.Get("pbk"));if err!=nil||len(key)!=32{return nil,invalid}
 sid,err:=hex.DecodeString(q.Get("sid"));if err!=nil||len(sid)>8||q.Get("sni")=="" {return nil,invalid}
 fp:=q.Get("fp");if fp==""{fp="chrome"}
 user:=map[string]any{"id":id,"encryption":"none"};if flow:=q.Get("flow");flow!=""{user["flow"]=flow}
 c:=map[string]any{
  "log":map[string]any{"loglevel":"none"},
  "outbounds":[]any{map[string]any{"tag":"maintenance-only","protocol":"vless",
   "settings":map[string]any{"vnext":[]any{map[string]any{"address":u.Hostname(),"port":port,"users":[]any{user}}}},
   "streamSettings":map[string]any{"network":"tcp","security":"reality","realitySettings":map[string]any{
    "serverName":q.Get("sni"),"fingerprint":fp,"publicKey":q.Get("pbk"),"shortId":q.Get("sid"),"spiderX":q.Get("spx")}},
  }},
 }
 return json.Marshal(c)
}

// CheckProfile validates structure without connecting to any network.
func CheckProfile(profile string) error {
 b,err:=configuration(profile);if err!=nil{return err}
 _,err=core.LoadConfig("json",strings.NewReader(string(b)))
 if err!=nil{return errors.New("unsupported private maintenance profile")};return nil
}

// Redirects must stay on the original HTTPS host; no playlist/plugin URL can widen this policy.
func redirectAllowed(original,current *url.URL) bool {
 return current.Scheme=="https" && current.Host==original.Host && current.User==nil && current.Fragment=="" && (current.String()==original.String() || CanFetch(current.String()))
}
func Fetch(profile,address string) (*Result,error) {
 if !CanFetch(address) {return nil,errors.New("not an approved built-in configuration")}
 original,_:=url.Parse(address)
 b,err:=configuration(profile);if err!=nil{return nil,err}
 instance,err:=core.StartInstance("json",b);if err!=nil{return nil,errors.New("maintenance route unavailable")}
 defer instance.Close()
 ctx,cancel:=context.WithTimeout(context.Background(),25*time.Second);defer cancel()
 transport:=&http.Transport{
  Proxy:nil,DisableKeepAlives:true,MaxResponseHeaderBytes:32*1024,TLSHandshakeTimeout:12*time.Second,
  DialContext:func(ctx context.Context,network,address string)(net.Conn,error){
   host,portText,err:=net.SplitHostPort(address);if err!=nil{return nil,errors.New("invalid destination")}
   port,err:=strconv.Atoi(portText);if err!=nil{return nil,err}
   // HTTPS destination is checked before dialing, independently of Java callers.
   if host!=original.Hostname()||port!=443{return nil,errors.New("destination outside maintenance scope")}
   return core.Dial(ctx,instance,xnet.TCPDestination(xnet.ParseAddress(host),xnet.Port(port)))
  },
 }
 defer transport.CloseIdleConnections()
 client:=&http.Client{Transport:transport,Timeout:25*time.Second,CheckRedirect:func(req *http.Request,via []*http.Request)error{
  if len(via)>=4 || !redirectAllowed(original,req.URL){return errors.New("redirect outside maintenance scope")};return nil
 }}
 request,err:=http.NewRequestWithContext(ctx,http.MethodGet,address,nil);if err!=nil{return nil,errors.New("invalid configuration address")}
 request.Header.Set("User-Agent","CineLive-Maintenance/1")
 response,err:=client.Do(request);if err!=nil{return nil,errors.New("maintenance configuration fetch failed")}
 defer response.Body.Close()
 if response.ContentLength>maxBytes{return nil,errors.New("configuration exceeds size limit")}
 data,err:=io.ReadAll(io.LimitReader(response.Body,maxBytes+1));if err!=nil||len(data)>maxBytes{return nil,errors.New("configuration read failed")}
 return &Result{Body:data,URL:response.Request.URL.String(),Status:response.StatusCode,RetryAfter:response.Header.Get("Retry-After")},nil
}
