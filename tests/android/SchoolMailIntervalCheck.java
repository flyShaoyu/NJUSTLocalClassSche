import com.classsche.mobile.SchoolMailIntervalPolicy;
import java.util.List;

public class SchoolMailIntervalCheck {
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  public static void main(String[] args){
    var policy=SchoolMailIntervalPolicy.INSTANCE;
    check(policy.getValues().equals(List.of(0,15,60,600,1440,2880,7200)),"Only requested intervals remain");
    for(int allowed:policy.getValues())check(policy.normalize(allowed)==allowed,"Existing supported choices remain unchanged");
    int[][] removed={{30,60},{120,600},{300,600},{1200,1440}};
    for(int[] change:removed){
      int value=policy.normalize(change[0]);
      check(value==change[1]&&value>change[0],"Removed interval migrates without making checks more frequent");
      check(policy.normalize(value)==value,"Migration is idempotent");
    }
    check(policy.normalize(0)==0,"Disabled stays disabled");
    check(policy.normalize(-1)==0&&policy.normalize(Integer.MAX_VALUE)==0,"Invalid stored values do not enable polling");
    System.out.println("PASS mail intervals: removed choices migrate to the next longer interval; supported values and disabled preserved");
  }
}
