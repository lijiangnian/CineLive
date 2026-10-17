package maintenancecore
import("testing";"strings";"github.com/xtls/xray-core/core")
func TestSyntheticConfigDiagnostic(t *testing.T){b,_:=configuration(profileTest);_,e:=core.LoadConfig("json",strings.NewReader(string(b)));if e!=nil{t.Fatal(e)}}

func TestCoreStartsWithoutListener(t *testing.T){b,_:=configuration(profileTest);instance,e:=core.StartInstance("json",b);if e!=nil{t.Fatal(e)};instance.Close()}
